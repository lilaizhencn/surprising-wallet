package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * BTC-like 链（BTC、LTC）P2WSH（SegWit 多签）一签抽象基类。
 *
 * <p>负责构建见证交易、计算手续费和找零、生成 P2WSH 见证脚本、
 * 使用 sig1 密钥分片对每个 UTXO 输入生成第一次部分签名。
 *
 * <p>派生路径：m/44'/{coinType}'/{biz}'/{userId}'/{index}
 * 签名使用 2-of-3 多签模型（sig1 + sig2 + recovery）。
 *
 * <p>子类可覆盖 {@link #getNetworkParameters()}、{@link #defaultFeeRate()}、
 * {@link #dustThresholdSat()} 来适配不同链的网络参数。
 */
@Slf4j
public abstract class AbstractBtcLikeFirstSign implements ISignService {

    protected final PubKeyConfig pubKeyConfig;
    protected final WalletKeyMaterialProvider keyMaterial;
    protected final String network;
    protected final ObjectMapper objectMapper;

    protected AbstractBtcLikeFirstSign(PubKeyConfig pubKeyConfig, WalletKeyMaterialProvider keyMaterial,
                                       ObjectMapper objectMapper, String network) {
        this.pubKeyConfig = Objects.requireNonNull(pubKeyConfig, "publicKeys");
        this.keyMaterial = Objects.requireNonNull(keyMaterial, "keys");
        this.objectMapper = Objects.requireNonNull(objectMapper, "mapper");
        this.network = Objects.requireNonNull(network, "network");
    }

    /** 默认费率（sat/vByte），子类可覆盖 */
    private static final long DEFAULT_FEE_RATE = 10L;
    /** 默认粉尘阈值（sat），子类可覆盖 */
    private static final long DUST_THRESHOLD_SAT = 546L;

    /**
     * 返回当前链的网络参数。
     *
     * @return BTC 测试网参数
     */
    protected NetworkParameters getNetworkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return MainNetParams.get();
        }
        return "regtest".equalsIgnoreCase(network)
                ? RegTestParams.get() : TestNet3Params.get();
    }

    /**
     * 对 BTC-like P2WSH 提现交易执行第一次签名。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析 utxos、addresses、withdraw 数组</li>
     *   <li>逐 UTXO 派生 BIP44 子密钥、生成 witness script、构建输入</li>
     *   <li>计算手续费（包含找零优化）和粉尘检查</li>
     *   <li>构建输出（提现 + 找零）</li>
     *   <li>使用 sig1 密钥分片生成 firstSignTx</li>
     *   <li>将 witnessScripts、utxoValues、scriptType 写入 signature</li>
     * </ol>
     *
     * @param transaction 提现交易
     */
    @Override
    public void signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        var builder = new WitnessTransactionBuilder(getNetworkParameters());
        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
        try {
            List<UtxoTransaction> utxos = JacksonJson.toList(objectMapper, signature.get("utxos"), UtxoTransaction.class);
            List<Address> addresses = JacksonJson.toList(objectMapper, signature.get("addresses"), Address.class);
            List<WithdrawRecord> payments = JacksonJson.toList(objectMapper, signature.get("withdraw"), WithdrawRecord.class);
            if (utxos.size() != addresses.size()) {
                throw new IllegalArgumentException("each input requires an address key path");
            }
            int inputCount = utxos.size();
            var scripts = new ArrayList<String>(inputCount);
            var inputValues = new ArrayList<Coin>(inputCount);
            var keys = new ArrayList<ECKey>(inputCount);
            BigDecimal unit = currency.getDecimal();
            long inputTotal = 0;
            for (int index = 0; index < inputCount; index++) {
                Address address = addresses.get(index);
                UtxoTransaction utxo = utxos.get(index);
                long atomic = utxo.getBalance().multiply(unit).longValueExact();
                inputTotal = Math.addExact(inputTotal, atomic);
                Coin value = Coin.valueOf(atomic);
                String script = pubKeyConfig.genWitnessScript(address, currency);
                keys.add(deriveKey(address, currency));
                scripts.add(script);
                inputValues.add(value);
                builder.addInput(utxo.getTxId(), utxo.getSeq(), script, value);
            }
            long total = transaction.getBalance().multiply(unit).longValueExact();
            if (total != inputTotal) {
                throw new IllegalArgumentException("signing total does not equal input amounts");
            }
            long sent = 0;
            for (WithdrawRecord payment : payments) {
                sent = Math.addExact(sent, payment.getBalance().multiply(unit).longValueExact());
            }
            long feeRate = JacksonJson.longValue(signature, "feeRate");
            if (feeRate <= 0) feeRate = defaultFeeRate();
            long remaining = Math.subtractExact(total, sent);
            long vBytes = WitnessTransactionBuilder.estimateVBytes(inputCount, payments.size());
            long fee = Math.multiplyExact(vBytes, feeRate);
            long change = Math.subtractExact(remaining, fee);
            long dust = dustThresholdSat();
            if (change >= dust) {
                long withChangeVBytes = WitnessTransactionBuilder.estimateVBytes(inputCount, payments.size() + 1);
                long withChangeFee = Math.multiplyExact(withChangeVBytes, feeRate);
                long withChangeAmount = Math.subtractExact(remaining, withChangeFee);
                if (withChangeAmount >= dust) {
                    vBytes = withChangeVBytes;
                    fee = withChangeFee;
                    change = withChangeAmount;
                } else {
                    fee = remaining;
                    change = 0;
                }
            } else if (change > 0) {
                fee = remaining;
                change = 0;
            }
            if (change < 0) throw new IllegalArgumentException("insufficient input for SegWit fee");
            for (WithdrawRecord payment : payments) {
                long amount = payment.getBalance().multiply(unit).longValueExact();
                if (amount > 0 && amount < dust) {
                    throw new IllegalArgumentException("withdraw output dust: " + amount + " sat");
                }
                builder.addOutput(payment.getAddress(), Coin.valueOf(amount));
            }
            if (change > 0) {
                String changeAddress = JacksonJson.text(signature, "changeAddress");
                if (changeAddress == null || changeAddress.isBlank()) {
                    throw new IllegalArgumentException("change address is required");
                }
                builder.addOutput(changeAddress, Coin.valueOf(change));
            }
            long estimatedWeight = P2wshFeeCalculator.estimateWeight(
                    inputCount, payments.size() + (change > 0 ? 1 : 0), 2, 3);
            signature.put("firstSignTx", builder.buildFirstSign(keys));
            signature.put("valid", true);
            ArrayNode scriptArray = objectMapper.createArrayNode();
            scripts.forEach(scriptArray::add);
            signature.set("witnessScripts", scriptArray);
            ArrayNode valueArray = objectMapper.createArrayNode();
            inputValues.forEach(value -> valueArray.add(value.getValue()));
            signature.set("utxoValues", valueArray);
            signature.put("scriptType", "p2wsh");
            signature.put("fee", fee);
            signature.put("estimatedVBytes", vBytes);
            signature.put("estimatedWeight", estimatedWeight);
            signature.put("vBytes", vBytes);
            log.info("P2WSH first sign: txid={} fee={} vBytes={}", builder.getHash(), fee, vBytes);
        } catch (Throwable error) {
            log.error("sign error", error);
            signature.put("valid", false);
            signature.put("error", error.getMessage());
        }
        transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
    }
    private ECKey deriveKey(Address address, AssetRuntimeMetadata currency) {
        return keyMaterial.sig1Root().getChild(44).getChild(currency.getBip44CoinType())
                .getChild(address.getBiz()).getChild(address.getUserId().intValue())
                .getChild(address.getIndex()).getEcKey();
    }

    /** @return 默认费率（sat/vByte） */
    protected long defaultFeeRate() { return DEFAULT_FEE_RATE; }
    /** @return 默认粉尘阈值（sat） */
    protected long dustThresholdSat() { return DUST_THRESHOLD_SAT; }
}

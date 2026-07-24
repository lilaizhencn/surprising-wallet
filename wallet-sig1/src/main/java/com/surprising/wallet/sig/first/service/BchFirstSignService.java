package com.surprising.wallet.sig.first.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BCH（Bitcoin Cash）第一次签名服务。
 *
 * <p>BCH 使用 P2SH 多签脚本（非 P2WSH），需逐 UTXO 构建输入、
 * 计算手续费（包含找零优化）和粉尘检查，使用 sig1 密钥分片对每个 UTXO 签名。
 * 签名模式为 bch-p2sh，二签由 {@code BchSecondSignService} 完成。
 *
 * <p>网络模式可通过配置 {@code sw.bch.network} 切换（mainnet/testnet/regtest）。
 */
@Component
public class BchFirstSignService implements ISignService {

    /** 多签公钥配置 */
    @Autowired
    private PubKeyConfig pubKeyConfig;

    /** 密钥材料提供者（sig1 模式） */
    @Autowired
    private WalletKeyMaterialProvider keyMaterial;

    /** BCH 网络模式：mainnet / testnet / regtest */
    @Value("${sw.bch.network:testnet}")
    private String network;

    /** @return 链名称 BCH */
    @Override
    public String chain() {
        return "BCH";
    }

    /**
     * 对 BCH P2SH 提现交易执行第一次签名。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析 utxos、addresses、withdraw 数组</li>
     *   <li>逐 UTXO 派生 redeemScript 并校验一致性</li>
     *   <li>使用 BitcoinCashFeePolicy 计算手续费和找零</li>
     *   <li>构建输出并调用 builder.buildFirstSign 签名</li>
     *   <li>将 firstSignTx、redeemScripts、scriptType=bch-p2sh 写入 signature</li>
     * </ol>
     *
     * @param transaction 提现交易
     */
    @Override
    public void signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {
            List<UtxoTransaction> utxos =
                    signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses =
                    signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> withdrawals =
                    signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
            if (utxos.size() != addresses.size()) {
                throw new IllegalArgumentException("BCH UTXO/address count mismatch");
            }
            BitcoinCashMultisigTransactionBuilder builder =
                    new BitcoinCashMultisigTransactionBuilder(networkParameters());
            List<ECKey> keys = new ArrayList<>();
            List<String> redeemScripts = new ArrayList<>();
            BigDecimal decimal = currency.getDecimal();
            for (int i = 0; i < utxos.size(); i++) {
                Address address = addresses.get(i);
                String derivedRedeemScript = pubKeyConfig.genRedeemScript(address, currency);
                if (address.getRedeemScript() != null
                        && !address.getRedeemScript().isBlank()
                        && !derivedRedeemScript.equalsIgnoreCase(address.getRedeemScript())) {
                    throw new IllegalArgumentException(
                            "stored redeemScript does not match derived BCH keys");
                }
                redeemScripts.add(derivedRedeemScript);
                keys.add(derive(address, currency).getEcKey());
                UtxoTransaction utxo = utxos.get(i);
                builder.addInput(
                        utxo.getTxId(),
                        utxo.getSeq(),
                        derivedRedeemScript,
                        Coin.valueOf(utxo.getBalance().multiply(decimal).longValueExact()));
            }
            long total = transaction.getBalance().multiply(decimal).longValueExact();
            long sent = withdrawals.stream()
                    .map(WithdrawRecord::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(decimal)
                    .longValueExact();
            long feeRate = signature.getLongValue("feeRate");
            if (feeRate <= 0) {
                feeRate = BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
            }
            long dust = signature.getLongValue("dustThreshold");
            if (dust <= 0) {
                dust = BitcoinCashFeePolicy.DUST_THRESHOLD_SAT;
            }
            BitcoinCashFeePolicy.SpendPlan spendPlan =
                    BitcoinCashFeePolicy.calculateSpendPlan(
                            total, sent, utxos.size(), withdrawals.size(), feeRate, dust);
            for (WithdrawRecord withdrawal : withdrawals) {
                long value = withdrawal.getBalance().multiply(decimal).longValueExact();
                if (value < dust) {
                    throw new IllegalArgumentException("BCH dust");
                }
                builder.addOutput(withdrawal.getAddress(), Coin.valueOf(value));
            }
            if (spendPlan.change() > 0) {
                String changeAddress = signature.getString("changeAddress");
                if (changeAddress == null || changeAddress.isBlank()) {
                    throw new IllegalArgumentException("missing BCH change address");
                }
                builder.addOutput(changeAddress, Coin.valueOf(spendPlan.change()));
            }
            signature.put("firstSignTx", builder.buildFirstSign(keys));
            signature.put("scriptType", "bch-p2sh");
            signature.put("fee", spendPlan.fee());
            signature.put("estimatedBytes", spendPlan.estimatedBytes());
            signature.put("valid", true);
            JSONArray scripts = new JSONArray();
            redeemScripts.forEach(scripts::add);
            signature.put("redeemScripts", scripts);
        } catch (Throwable error) {
            signature.put("valid", false);
            signature.put("error", error.getMessage());
        }
        transaction.setSignature(signature.toJSONString());
    }

    /**
     * 按 BIP44 路径从 sig1 根密钥派生地址对应的子密钥。
     *
     * <p>派生路径：m/44'/{coinType}'/{biz}'/{userId}'/{index}
     *
     * @param address  地址信息
     * @param currency 资产元数据
     * @return 派生后的 BIP32 节点
     */
    private Bip32Node derive(Address address, AssetRuntimeMetadata currency) {
        return keyMaterial.sig1Root().getChild(44)
                .getChild(currency.getBip44CoinType())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
    }

    /**
     * 根据配置的 {@link #network} 返回对应的 BCH 网络参数。
     *
     * @return BCH 网络参数
     */
    private BitcoinCashNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return BitcoinCashNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? BitcoinCashNetworkParameters.regtest()
                : BitcoinCashNetworkParameters.testnet();
    }
}

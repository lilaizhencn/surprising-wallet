package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BCH（Bitcoin Cash）第二次签名服务。
 *
 * <p>BCH 使用 P2SH 多签脚本（非 P2WSH），签名流程与 BTC/LTC 不同：
 * 需要 redeemScript 和完整 UTXO 信息，通过 {@link BitcoinCashMultisigTransactionBuilder}
 * 构建第二签。
 *
 * <p>网络模式可通过配置 {@code sw.bch.network} 切换（mainnet/testnet/regtest）。
 */
@Component
public class BchSecondSignService implements ISignService {

    /** BCH 网络模式：mainnet / testnet / regtest */
    @Value("${sw.bch.network:testnet}")
    private String network;

    /** @return 链名称 BCH */
    @Override
    public String chain() {
        return "BCH";
    }

    /**
     * 对 BCH P2SH 提现交易执行第二次签名。
     *
     * <p>要求脚本类型为 bch-p2sh，校验 address、utxo、redeemScript 数量一致后，
     * 逐输入派生 BIP32 私钥并委托 {@link BitcoinCashMultisigTransactionBuilder} 完成签名。
     *
     * @param transaction 提现交易
     * @return 签名后的交易十六进制字符串，失败时设置 valid=false 并返回空字符串
     */
    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {
            if (!"bch-p2sh".equals(signature.getString("scriptType"))) {
                throw new IllegalArgumentException("not BCH P2SH");
            }
            List<Address> addresses =
                    signature.getJSONArray("addresses").toJavaList(Address.class);
            List<UtxoTransaction> utxos =
                    signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<String> redeemScripts =
                    signature.getJSONArray("redeemScripts").toJavaList(String.class);
            if (addresses.size() != utxos.size()
                    || addresses.size() != redeemScripts.size()) {
                throw new IllegalArgumentException("BCH signing input metadata mismatch");
            }
            List<ECKey> keys = new ArrayList<>();
            BitcoinCashMultisigTransactionBuilder builder =
                    new BitcoinCashMultisigTransactionBuilder(networkParameters());
            BigDecimal decimal = currency.getDecimal();
            for (int i = 0; i < addresses.size(); i++) {
                keys.add(BipNodeUtil.getBipNODE(addresses.get(i), currency).getEcKey());
                UtxoTransaction utxo = utxos.get(i);
                builder.addInput(
                        utxo.getTxId(),
                        utxo.getSeq(),
                        redeemScripts.get(i),
                        Coin.valueOf(utxo.getBalance().multiply(decimal).longValueExact()));
            }
            return builder.buildSecondSign(
                    signature.getString("firstSignTx"), keys, redeemScripts);
        } catch (Throwable error) {
            signature.put("valid", false);
            signature.put("error", error.getMessage());
            transaction.setSignature(signature.toJSONString());
            return "";
        }
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

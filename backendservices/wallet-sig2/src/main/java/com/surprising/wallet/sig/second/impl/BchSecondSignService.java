package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
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

@Component
public class BchSecondSignService implements ISignService {
    @Value("${atomex.bch.network:testnet}")
    private String network;

    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.BCH;
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
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
            BigDecimal decimal = RuntimeAsset.BCH.getDecimal();
            for (int i = 0; i < addresses.size(); i++) {
                keys.add(BipNodeUtil.getBipNODE(addresses.get(i)).getEcKey());
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

    private BitcoinCashNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return BitcoinCashNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? BitcoinCashNetworkParameters.regtest()
                : BitcoinCashNetworkParameters.testnet();
    }
}

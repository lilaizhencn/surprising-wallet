package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Second signer for Dogecoin legacy P2SH 2-of-3 transactions.
 */
@Component
public class DogeSecondSignService implements ISignService {
    @Value("${sw.doge.network:testnet}")
    private String network;

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        RuntimeAsset currency = RuntimeAsset.fromTransaction(transaction);
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {
            if (!"p2sh".equals(signature.getString("scriptType"))) {
                throw new IllegalArgumentException("not DOGE P2SH");
            }
            String firstSigned = signature.getString("firstSignTx");
            List<Address> addresses = signature.getJSONArray("addresses").toJavaList(Address.class);
            List<String> redeemScripts = signature.getJSONArray("redeemScripts").toJavaList(String.class);
            List<ECKey> keys = new ArrayList<>(addresses.size());
            for (Address address : addresses) {
                keys.add(BipNodeUtil.getBipNODE(address, currency).getEcKey());
            }
            LegacyMultisigTransactionBuilder builder =
                    new LegacyMultisigTransactionBuilder(networkParameters());
            return builder.buildSecondSign(firstSigned, keys, redeemScripts);
        } catch (Throwable error) {
            signature.put("valid", false);
            signature.put("error", error.getMessage());
            transaction.setSignature(signature.toJSONString());
            return "";
        }
    }

    @Override
    public String chain() {
        return "DOGE";
    }

    private DogecoinNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return DogecoinNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? DogecoinNetworkParameters.regtest()
                : DogecoinNetworkParameters.testnet();
    }
}

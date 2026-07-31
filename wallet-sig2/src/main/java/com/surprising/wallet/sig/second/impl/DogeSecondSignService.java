package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
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
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Component
public class DogeSecondSignService implements ISignService {
    /**
     * 保存 {@code network}，表示链、网络、资产或代币配置。
     */
    @Value("${sw.doge.network:testnet}")
    private String network;

    /**
     * 为 {@code signTransaction} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
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

    /**
     * 获取或查询 {@code chain} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String chain() {
        return "DOGE";
    }

    /**
     * 获取或查询 {@code networkParameters} 对应的数据，并向调用方返回当前业务状态。
     */
    private DogecoinNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return DogecoinNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? DogecoinNetworkParameters.regtest()
                : DogecoinNetworkParameters.testnet();
    }
}

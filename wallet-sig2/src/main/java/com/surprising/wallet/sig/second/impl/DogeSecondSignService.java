package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Component
public class DogeSecondSignService implements ISignService {
    /** Jackson 3 对象映射器，用于解析和序列化签名元数据。 */
    @Autowired
    private ObjectMapper objectMapper;
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
        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
        try {
            if (!"p2sh".equals(JacksonJson.text(signature, "scriptType"))) {
                throw new IllegalArgumentException("not DOGE P2SH");
            }
            String firstSigned = JacksonJson.text(signature, "firstSignTx");
            List<Address> addresses = JacksonJson.toList(objectMapper, signature.get("addresses"), Address.class);
            List<String> redeemScripts = JacksonJson.toList(objectMapper, signature.get("redeemScripts"), String.class);
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
            transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
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

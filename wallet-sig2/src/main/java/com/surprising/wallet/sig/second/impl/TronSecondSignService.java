package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.tron.TronWalletApi;
import org.tron.protos.Protocol;
import org.tron.wallet.util.ByteArray;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * TRON（波场）第二次签名服务。
 *
 * <p>使用 TronWalletApi 创建并签名 TRON 交易。
 * 私钥由 sig2 BIP32 根密钥按 BIP44 路径派生：
 * m/44'/{coinType}'/{biz}'/{userId}'/{index}。
 *
 * @author atomex
 */
@Component
@Slf4j
public class TronSecondSignService implements ISignService {

    /** @return 链名称 TRON */
    @Override
    public String chain() {
        return "TRON";
    }

    /** @return 资产符号 TRX（TRON 主币） */
    @Override
    public String assetSymbol() {
        return "TRX";
    }

    /**
     * 对 TRON 提现交易执行第二次签名。
     *
     * <p>解析签名 JSON 中的 address、from、to、block 信息，
     * 创建 TRON 原始交易后使用 sig2 私钥签名。
     *
     * @param transaction 提现交易
     * @return 签名后的交易十六进制字符串，失败时返回空字符串
     */
    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        JSONObject sigJson = JSONObject.parseObject(transaction.getSignature());
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        String from = sigJson.getString("from");
        String toAddr = sigJson.getString("to");
        BigDecimal value = transaction.getBalance();
        try {
            Protocol.Transaction waitSignTx = TronWalletApi.createTransaction(TronWalletApi.decodeFromBase58Check(from), TronWalletApi.decodeFromBase58Check(toAddr),
                    value.multiply(currency.getDecimal()).longValue(),
                    sigJson.getString("block"));
            Protocol.Transaction signedTxObject = TronWalletApi.signTransaction2Object(
                    waitSignTx.toByteArray(), getKeyByAddress(address, currency));
            if (!ObjectUtils.isEmpty(signedTxObject)) {
                return ByteArray.toHexString(signedTxObject.toByteArray());
            }
            return "";
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * 根据地址和资产信息从 BIP32 派生私钥字节。
     *
     * <p>wallet-service 和 wallet-sig2 使用相同的 sig2 种子（存储在 wallet_key_config），
     * 按路径 m/44'/{coinType}'/{biz}'/{userId}'/{index} 派生。
     *
     * @param address  地址信息（包含 biz、userId、index）
     * @param currency 资产元数据（提供 coinType）
     * @return 32 字节私钥
     */
    private byte[] getKeyByAddress(Address address, AssetRuntimeMetadata currency) {
        return BipNodeUtil.getBipNODE(address, currency).getEcKey().getPrivKeyBytes();
    }

}

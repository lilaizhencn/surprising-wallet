package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
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
 * @author atomex
 */
@Component
@Slf4j
public class TronSecondSignService implements ISignService {
    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.TRX;
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        JSONObject sigJson = JSONObject.parseObject(transaction.getSignature());
        RuntimeAsset currency = RuntimeAsset.fromTransaction(transaction);
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

    private byte[] getKeyByAddress(Address address, RuntimeAsset currency) {
        /*
         * Keep TRON on the same root-key model as BTC/EVM:
         * wallet-server derives the public key from wallet.pubKey2 at
         * m/44/currency/biz/user/index, and sig2 derives the matching private key
         * from atomex.wallet.masterKey at the identical path.
         */
        return BipNodeUtil.getBipNODE(address, currency).getEcKey().getPrivKeyBytes();
    }

}

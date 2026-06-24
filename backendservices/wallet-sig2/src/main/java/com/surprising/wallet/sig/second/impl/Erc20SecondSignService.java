package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class Erc20SecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.USDT;
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        RuntimeAsset currency = RuntimeAsset.parseValue(transaction.getCurrency());
        String sigStr = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(sigStr);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        Bip32Node node = BipNodeUtil.getBipNODE(address);
        String signResult = tokenTransaction(
                sigJson.getBigDecimal("gasPrice").multiply(RuntimeAsset.ETH.getDecimal()).toBigInteger(),
                sigJson.getBigDecimal("gas").multiply(RuntimeAsset.ETH.getDecimal()).toBigInteger(),
                BigInteger.valueOf(address.getNonce()),
                node.getEcKey().getPrivateKeyAsHex(),
                currency.getContractAddress(),
                sigJson.getString("to"),
                transaction.getBalance().multiply(currency.getDecimal()).toBigInteger(),
                sigJson.containsKey("chainId") ? sigJson.getLongValue("chainId") : org.web3j.tx.ChainIdLong.NONE);
        return signResult;
    }
}

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
import java.math.BigDecimal;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class Erc20SecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    @Override
    public String chain() {
        return "ETH";
    }

    @Override
    public String assetSymbol() {
        return "*";
    }

    @Override
    public boolean supports(RuntimeAsset asset) {
        return asset != null
                && chain().equalsIgnoreCase(asset.chain())
                && !chain().equalsIgnoreCase(asset.assetSymbol())
                && !asset.getContractAddress().isBlank();
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        RuntimeAsset currency = RuntimeAsset.fromTransaction(transaction);
        String sigStr = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(sigStr);
        BigDecimal feeDecimal = feeDecimal(sigJson, currency);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        Bip32Node node = BipNodeUtil.getBipNODE(address, currency);
        String signResult = tokenTransaction(
                sigJson.getBigDecimal("gasPrice").multiply(feeDecimal).toBigInteger(),
                sigJson.getBigDecimal("gas").multiply(feeDecimal).toBigInteger(),
                BigInteger.valueOf(address.getNonce()),
                node.getEcKey().getPrivateKeyAsHex(),
                currency.getContractAddress(),
                sigJson.getString("to"),
                transaction.getBalance().multiply(currency.getDecimal()).toBigInteger(),
                sigJson.containsKey("chainId") ? sigJson.getLongValue("chainId") : org.web3j.tx.ChainIdLong.NONE);
        return signResult;
    }
}

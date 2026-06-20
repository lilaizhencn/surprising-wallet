package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.tx.ChainIdLong;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractEthLikeSecondSign implements ISignService {
    public static final String PREFIX = "0x";

    public static byte[] StringHexToByteArray(String x) {
        if (x.startsWith(PREFIX)) {
            x = x.substring(2);
        }
        if (x.length() % 2 != 0) {
            x = "0" + x;
        }
        return Hex.decode(x);
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        String sigStr = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(sigStr);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        Bip32Node node = BipNodeUtil.getBipNODE(address);
        String signResult = sign(
                BigInteger.valueOf(address.getNonce()),
                sigJson.getBigDecimal("gasPrice").multiply(CurrencyEnum.ETH.getDecimal()).toBigInteger(),
                sigJson.getBigDecimal("gas").multiply(getCurrency().getDecimal()).toBigInteger(),
                sigJson.getString("to"),
                transaction.getBalance().multiply(getCurrency().getDecimal()).toBigInteger(),
                "",
                sigJson.containsKey("chainId") ? sigJson.getLongValue("chainId") : ChainIdLong.MAINNET,
                node.getEcKey().getPrivateKeyAsHex());
        return signResult;
    }


    public static String tokenTransaction(BigInteger gasPrice, BigInteger gasLimit, BigInteger nonce, String privateKey, String contractAddress, String toAddress, BigInteger amount) {
        return tokenTransaction(gasPrice, gasLimit, nonce, privateKey, contractAddress, toAddress, amount, ChainIdLong.NONE);
    }

    public static String tokenTransaction(BigInteger gasPrice, BigInteger gasLimit, BigInteger nonce, String privateKey, String contractAddress, String toAddress, BigInteger amount, long chainId) {
        BigInteger value = BigInteger.ZERO;
        //token转账参数
        String methodName = "transfer";
        List<Type> inputParameters = new ArrayList<>();
        List<TypeReference<?>> outputParameters = new ArrayList<>();
        org.web3j.abi.datatypes.Address tAddress = new org.web3j.abi.datatypes.Address(toAddress);
        Uint256 tokenValue = new Uint256(amount);
        inputParameters.add(tAddress);
        inputParameters.add(tokenValue);
        TypeReference<Bool> typeReference = new TypeReference<Bool>() {
        };
        outputParameters.add(typeReference);
        Function function = new Function(methodName, inputParameters, outputParameters);
        String data = FunctionEncoder.encode(function);

        String signedData;
        signedData = sign(nonce, gasPrice, gasLimit, contractAddress, value, data, chainId, privateKey);
        return signedData;
    }

    /**
     * 签名交易
     */
    public static String sign(BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit, String to,
                              BigInteger value, String data, long chainId, String privateKey) {
        byte[] signedMessage;
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                to,
                value,
                data);

        if (privateKey.startsWith("0x")) {
            privateKey = privateKey.substring(2);
        }
        ECKeyPair ecKeyPair = ECKeyPair.create(new BigInteger(privateKey, 16));
        Credentials credentials = Credentials.create(ecKeyPair);
        if (chainId > ChainIdLong.NONE) {
            signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        } else {
            signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
        }
        return Numeric.toHexString(signedMessage);
    }
}

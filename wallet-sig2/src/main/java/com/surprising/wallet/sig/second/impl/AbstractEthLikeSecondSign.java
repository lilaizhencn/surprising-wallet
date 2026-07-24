package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * EVM 兼容链（ETH、BNB、POLYGON 等）的二签抽象基类。
 *
 * <p>提供 EVM 交易签名的公共逻辑：原始交易编码、ERC20 代币 transfer 函数编码、
 * 以及基于 web3j 的 EIP-155 签名。子类只需实现 {@link ISignService#chain()}
 * 和 {@link ISignService#assetSymbol()} 即可完成特定链/资产的适配。
 *
 * @author atomex
 */
@Slf4j
abstract public class AbstractEthLikeSecondSign implements ISignService {

    /** 十六进制字符串前缀 */
    public static final String PREFIX = "0x";

    /**
     * 将带 0x 前缀的十六进制字符串转为字节数组。
     *
     * @param x 十六进制字符串
     * @return 解码后的字节数组
     */
    public static byte[] StringHexToByteArray(String x) {
        if (x.startsWith(PREFIX)) {
            x = x.substring(2);
        }
        if (x.length() % 2 != 0) {
            x = "0" + x;
        }
        return Hex.decode(x);
    }

    /**
     * 对 EVM 提现交易执行第二次签名。
     *
     * <p>解析签名元数据中的 nonce、gasPrice、gas、to、chainId，
     * 通过 BIP32 派生地址对应的私钥后调用 {@link #sign} 完成签名。
     *
     * @param transaction 提现交易
     * @return 签名后的交易十六进制字符串
     */
    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        String sigStr = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(sigStr);
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        BigDecimal feeDecimal = feeDecimal(sigJson, currency);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        Bip32Node node = BipNodeUtil.getBipNODE(address, currency);
        String signResult = sign(
                BigInteger.valueOf(address.getNonce()),
                sigJson.getBigDecimal("gasPrice").multiply(feeDecimal).toBigInteger(),
                sigJson.getBigDecimal("gas").multiply(feeDecimal).toBigInteger(),
                sigJson.getString("to"),
                transaction.getBalance().multiply(currency.getDecimal()).toBigInteger(),
                "",
                sigJson.containsKey("chainId") ? sigJson.getLongValue("chainId") : ChainIdLong.MAINNET,
                node.getEcKey().getPrivateKeyAsHex());
        return signResult;
    }

    /**
     * 计算手续费的精度因子（10^decimals），用于将 gasPrice/gasLimit 转换为最小单位。
     *
     * @param sigJson 签名 JSON（可包含 feeAssetDecimals 字段）
     * @param currency 资产元数据
     * @return 精度因子
     */
    protected BigDecimal feeDecimal(JSONObject sigJson, AssetRuntimeMetadata currency) {
        Integer decimals = sigJson.getObject("feeAssetDecimals", Integer.class);
        return BigDecimal.TEN.pow(decimals == null ? currency.getDecimals() : decimals);
    }


    /**
     * 构建并签名 ERC20 代币转账交易（无 chainId）。
     *
     * @param gasPrice  gas 价格（最小单位）
     * @param gasLimit  gas 限制
     * @param nonce     发送方 nonce
     * @param privateKey 发送方私钥十六进制
     * @param contractAddress ERC20 代币合约地址
     * @param toAddress 接收方地址
     * @param amount    代币数量（最小单位）
     * @return 签名后的交易十六进制字符串
     */
    public static String tokenTransaction(BigInteger gasPrice, BigInteger gasLimit, BigInteger nonce, String privateKey, String contractAddress, String toAddress, BigInteger amount) {
        return tokenTransaction(gasPrice, gasLimit, nonce, privateKey, contractAddress, toAddress, amount, ChainIdLong.NONE);
    }

    /**
     * 构建并签名 ERC20 代币转账交易（指定 chainId，支持 EIP-155 重放保护）。
     *
     * <p>使用 ABI 编码构造 ERC20 transfer(address,uint256) 函数调用，
     * 然后将调用数据作为 EVM 交易的 data 字段进行签名。
     *
     * @param gasPrice  gas 价格（最小单位）
     * @param gasLimit  gas 限制
     * @param nonce     发送方 nonce
     * @param privateKey 发送方私钥十六进制
     * @param contractAddress ERC20 代币合约地址
     * @param toAddress 接收方地址
     * @param amount    代币数量（最小单位）
     * @param chainId   EVM 链 ID
     * @return 签名后的交易十六进制字符串
     */
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

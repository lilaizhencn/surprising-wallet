package com.surprising.wallet.chain.evm;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

/**
 * 负责交易或消息签名，并保持签名材料和编码规则一致。
 */
public class Evm7702OperationSigner {
    /**
     * 定义 {@code DOMAIN_TYPEHASH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final byte[] DOMAIN_TYPEHASH = hashUtf8(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    /**
     * 定义 {@code NAME_HASH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final byte[] NAME_HASH = hashUtf8("SurprisingWallet7702Collection");
    /**
     * 定义 {@code VERSION_HASH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final byte[] VERSION_HASH = hashUtf8("1");
    /**
     * 定义 {@code COLLECTION_TYPEHASH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final byte[] COLLECTION_TYPEHASH = hashUtf8(
            "CollectionRequest(bytes32 batchId,uint256 itemIndex,address authority,address collector,address token,address recipient,uint256 amount,uint256 operationNonce,uint256 deadline,uint256 callGasLimit)");
    /**
     * 执行 {@code digest} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public byte[] digest(BigInteger chainId, Evm7702CollectionRequest request) {
        if (chainId == null || chainId.signum() <= 0 || chainId.bitLength() > 256) {
            throw new IllegalArgumentException("chainId must be a positive uint256");
        }
        byte[] domainSeparator = abiHash(List.of(
                new Bytes32(DOMAIN_TYPEHASH),
                new Bytes32(NAME_HASH),
                new Bytes32(VERSION_HASH),
                new Uint256(chainId),
                new Address(request.authority())
        ));
        byte[] structHash = abiHash(List.of(
                new Bytes32(COLLECTION_TYPEHASH),
                new Bytes32(request.batchId()),
                new Uint256(request.itemIndex()),
                new Address(request.authority()),
                new Address(request.collector()),
                new Address(request.token()),
                new Address(request.recipient()),
                new Uint256(request.amount()),
                new Uint256(request.operationNonce()),
                new Uint256(request.deadline()),
                new Uint256(request.callGasLimit())
        ));
        byte[] payload = new byte[66];
        payload[0] = 0x19;
        payload[1] = 0x01;
        System.arraycopy(domainSeparator, 0, payload, 2, 32);
        System.arraycopy(structHash, 0, payload, 34, 32);
        return Hash.sha3(payload);
    }
    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public byte[] sign(BigInteger chainId, Evm7702CollectionRequest request, Credentials authority) {
        if (authority == null || !authority.getAddress().equalsIgnoreCase(request.authority())) {
            throw new IllegalArgumentException("authority credentials do not match request authority");
        }
        Sign.SignatureData signature = Sign.signMessage(digest(chainId, request), authority.getEcKeyPair(), false);
        byte[] encoded = new byte[65];
        System.arraycopy(signature.getR(), 0, encoded, 0, 32);
        System.arraycopy(signature.getS(), 0, encoded, 32, 32);
        encoded[64] = signature.getV()[0];
        return encoded;
    }
    /**
     * 编码 {@code abiHash} 对应的数据，生成链上或接口所需的表示。
     */
    private static byte[] abiHash(List<Type> values) {
        return Hash.sha3(Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(values)));
    }
    /**
     * 判断 {@code hashUtf8} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static byte[] hashUtf8(String value) {
        return Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

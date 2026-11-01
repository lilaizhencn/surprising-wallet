package com.surprising.wallet.chain.evm;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

/**
 * 仅计算固定 EIP-712 消息摘要；签名由独立 MPC 持有者完成。
 */
public class Evm7702OperationSigner {
    static final byte[] DOMAIN_TYPEHASH = hashUtf8(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    static final byte[] NAME_HASH = hashUtf8("SurprisingWallet7702Collection");
    static final byte[] VERSION_HASH = hashUtf8("1");
    static final byte[] COLLECTION_TYPEHASH = hashUtf8(
            "CollectionRequest(bytes32 batchId,uint256 itemIndex,address authority,address collector,address token,address recipient,uint256 amount,uint256 operationNonce,uint256 deadline,uint256 callGasLimit)");
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

    private static byte[] abiHash(List<Type> values) {
        return Hash.sha3(Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(values)));
    }
    private static byte[] hashUtf8(String value) {
        return Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

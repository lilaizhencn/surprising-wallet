package com.surprising.wallet.service.chain.evm;

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

/** EIP-712 signer for the exact payout batch consumed by Eip7702PayoutDelegate. */
public class Evm7702PayoutSigner {
    static final byte[] DOMAIN_TYPEHASH = hashUtf8(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    static final byte[] NAME_HASH = hashUtf8("SurprisingWallet7702Payout");
    static final byte[] VERSION_HASH = hashUtf8("1");
    static final byte[] ITEM_TYPEHASH = hashUtf8(
            "PayoutItem(bytes32 withdrawalId,uint256 itemIndex,address token,address recipient,uint256 amount,uint256 callGasLimit)");
    static final byte[] REQUEST_TYPEHASH = hashUtf8(
            "PayoutRequest(bytes32 batchId,address authority,address executor,bytes32 itemsHash,uint256 operationNonce,uint256 deadline)");

    public byte[] digest(BigInteger chainId, Evm7702PayoutRequest request) {
        Evm7702PayoutItem.requireUint(chainId, "chainId", false);
        byte[] domainSeparator = abiHash(List.of(
                new Bytes32(DOMAIN_TYPEHASH), new Bytes32(NAME_HASH), new Bytes32(VERSION_HASH),
                new Uint256(chainId), new Address(request.authority())));
        byte[] structHash = abiHash(List.of(
                new Bytes32(REQUEST_TYPEHASH), new Bytes32(request.batchId()),
                new Address(request.authority()), new Address(request.executor()),
                new Bytes32(itemsHash(request.items())), new Uint256(request.operationNonce()),
                new Uint256(request.deadline())));
        byte[] payload = new byte[66];
        payload[0] = 0x19;
        payload[1] = 0x01;
        System.arraycopy(domainSeparator, 0, payload, 2, 32);
        System.arraycopy(structHash, 0, payload, 34, 32);
        return Hash.sha3(payload);
    }

    public byte[] itemsHash(List<Evm7702PayoutItem> items) {
        byte[] encoded = new byte[items.size() * 32];
        for (int index = 0; index < items.size(); index++) {
            Evm7702PayoutItem item = items.get(index);
            byte[] hash = abiHash(List.of(
                    new Bytes32(ITEM_TYPEHASH), new Bytes32(item.withdrawalId()),
                    new Uint256(item.itemIndex()), new Address(item.token()),
                    new Address(item.recipient()), new Uint256(item.amount()),
                    new Uint256(item.callGasLimit())));
            System.arraycopy(hash, 0, encoded, index * 32, 32);
        }
        return Hash.sha3(encoded);
    }

    public byte[] sign(BigInteger chainId, Evm7702PayoutRequest request, Credentials authority) {
        if (authority == null || !authority.getAddress().equalsIgnoreCase(request.authority())) {
            throw new IllegalArgumentException("authority credentials do not match payout request");
        }
        Sign.SignatureData signature = Sign.signMessage(digest(chainId, request), authority.getEcKeyPair(), false);
        byte[] encoded = new byte[65];
        System.arraycopy(signature.getR(), 0, encoded, 0, 32);
        System.arraycopy(signature.getS(), 0, encoded, 32, 32);
        encoded[64] = signature.getV()[0];
        return encoded;
    }

    private static byte[] abiHash(List<Type> values) {
        return Hash.sha3(Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(values)));
    }

    private static byte[] hashUtf8(String value) {
        return Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

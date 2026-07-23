package com.surprising.wallet.service.chain.near;

import org.bitcoinj.base.Base58;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class NearTransactionSigner {
    private static final int ED25519_KEY_TYPE = 0;
    private static final int DEPLOY_CONTRACT_ACTION = 1;
    private static final int FUNCTION_CALL_ACTION = 2;
    private static final int TRANSFER_ACTION = 3;

    private final NearKeyService keyService;

    public NearTransactionSigner(NearKeyService keyService) {
        this.keyService = keyService;
    }

    public SignedTransaction transfer(long userId, int biz, long addressIndex,
                                      String signerId, long nonce, String receiverId,
                                      String blockHashBase58, BigInteger amountYocto) {
        byte[] publicKey = keyService.derive(userId, biz, addressIndex).publicKey();
        byte[] transaction = transactionBytes(
                signerId, publicKey, nonce, receiverId, Base58.decode(blockHashBase58), amountYocto);
        byte[] hash = sha256(transaction);
        byte[] signature = keyService.sign(userId, biz, addressIndex, hash);
        byte[] signedTransaction = signedTransactionBytes(transaction, signature);
        return new SignedTransaction(
                Base58.encode(hash),
                Base64.getEncoder().encodeToString(signedTransaction),
                Base58.encode(publicKey));
    }

    public SignedTransaction functionCall(long userId, int biz, long addressIndex,
                                          String signerId, long nonce, String receiverId,
                                          String blockHashBase58, String methodName,
                                          byte[] args, long gas, BigInteger depositYocto) {
        byte[] publicKey = keyService.derive(userId, biz, addressIndex).publicKey();
        byte[] transaction = functionCallTransactionBytes(
                signerId, publicKey, nonce, receiverId, Base58.decode(blockHashBase58),
                methodName, args, gas, depositYocto);
        byte[] hash = sha256(transaction);
        byte[] signature = keyService.sign(userId, biz, addressIndex, hash);
        byte[] signedTransaction = signedTransactionBytes(transaction, signature);
        return new SignedTransaction(
                Base58.encode(hash),
                Base64.getEncoder().encodeToString(signedTransaction),
                Base58.encode(publicKey));
    }

    public SignedTransaction deployContractAndFunctionCall(long userId, int biz, long addressIndex,
                                                           String signerId, long nonce, String receiverId,
                                                           String blockHashBase58, byte[] contractCode,
                                                           String methodName, byte[] args,
                                                           long gas, BigInteger depositYocto) {
        byte[] publicKey = keyService.derive(userId, biz, addressIndex).publicKey();
        byte[] transaction = deployContractAndFunctionCallTransactionBytes(
                signerId, publicKey, nonce, receiverId, Base58.decode(blockHashBase58),
                contractCode, methodName, args, gas, depositYocto);
        byte[] hash = sha256(transaction);
        byte[] signature = keyService.sign(userId, biz, addressIndex, hash);
        byte[] signedTransaction = signedTransactionBytes(transaction, signature);
        return new SignedTransaction(
                Base58.encode(hash),
                Base64.getEncoder().encodeToString(signedTransaction),
                Base58.encode(publicKey));
    }

    static byte[] transactionBytes(String signerId, byte[] publicKey, long nonce,
                                   String receiverId, byte[] blockHash, BigInteger amountYocto) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("NEAR Ed25519 public key must be 32 bytes");
        }
        if (blockHash == null || blockHash.length != 32) {
            throw new IllegalArgumentException("NEAR block hash must be 32 bytes");
        }
        BorshWriter writer = new BorshWriter();
        writer.string(signerId);
        writer.publicKey(publicKey);
        writer.u64(nonce);
        writer.string(receiverId);
        writer.bytes(blockHash);
        writer.u32(1);
        writer.u8(TRANSFER_ACTION);
        writer.u128(amountYocto);
        return writer.toByteArray();
    }

    static byte[] functionCallTransactionBytes(String signerId, byte[] publicKey, long nonce,
                                               String receiverId, byte[] blockHash, String methodName,
                                               byte[] args, long gas, BigInteger depositYocto) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("NEAR Ed25519 public key must be 32 bytes");
        }
        if (blockHash == null || blockHash.length != 32) {
            throw new IllegalArgumentException("NEAR block hash must be 32 bytes");
        }
        if (gas < 0) {
            throw new IllegalArgumentException("NEAR gas must be unsigned");
        }
        BorshWriter writer = new BorshWriter();
        writer.string(signerId);
        writer.publicKey(publicKey);
        writer.u64(nonce);
        writer.string(receiverId);
        writer.bytes(blockHash);
        writer.u32(1);
        writer.u8(FUNCTION_CALL_ACTION);
        writer.string(methodName);
        writer.byteArray(args == null ? new byte[0] : args);
        writer.u64(gas);
        writer.u128(depositYocto);
        return writer.toByteArray();
    }

    static byte[] deployContractAndFunctionCallTransactionBytes(String signerId, byte[] publicKey, long nonce,
                                                                String receiverId, byte[] blockHash,
                                                                byte[] contractCode, String methodName,
                                                                byte[] args, long gas,
                                                                BigInteger depositYocto) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("NEAR Ed25519 public key must be 32 bytes");
        }
        if (blockHash == null || blockHash.length != 32) {
            throw new IllegalArgumentException("NEAR block hash must be 32 bytes");
        }
        if (contractCode == null || contractCode.length == 0) {
            throw new IllegalArgumentException("NEAR contract code must not be empty");
        }
        if (gas < 0) {
            throw new IllegalArgumentException("NEAR gas must be unsigned");
        }
        BorshWriter writer = new BorshWriter();
        writer.string(signerId);
        writer.publicKey(publicKey);
        writer.u64(nonce);
        writer.string(receiverId);
        writer.bytes(blockHash);
        writer.u32(2);
        writer.u8(DEPLOY_CONTRACT_ACTION);
        writer.byteArray(contractCode);
        writer.u8(FUNCTION_CALL_ACTION);
        writer.string(methodName);
        writer.byteArray(args == null ? new byte[0] : args);
        writer.u64(gas);
        writer.u128(depositYocto);
        return writer.toByteArray();
    }

    private static byte[] signedTransactionBytes(byte[] transaction, byte[] signature) {
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException("NEAR Ed25519 signature must be 64 bytes");
        }
        BorshWriter writer = new BorshWriter();
        writer.bytes(transaction);
        writer.u8(ED25519_KEY_TYPE);
        writer.bytes(signature);
        return writer.toByteArray();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record SignedTransaction(String transactionHash, String signedTransactionBase64, String publicKeyBase58) {
    }

    private static final class BorshWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void u8(int value) {
            out.write(value & 0xff);
        }

        void u32(int value) {
            bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }

        void u64(long value) {
            bytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void u128(BigInteger value) {
            if (value == null || value.signum() < 0 || value.bitLength() > 128) {
                throw new IllegalArgumentException("NEAR amount must fit unsigned 128-bit integer");
            }
            byte[] bigEndian = value.toByteArray();
            byte[] littleEndian = new byte[16];
            for (int i = 0; i < bigEndian.length; i++) {
                int source = bigEndian.length - 1 - i;
                if (i < littleEndian.length) {
                    littleEndian[i] = bigEndian[source];
                }
            }
            bytes(littleEndian);
        }

        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            u32(bytes.length);
            bytes(bytes);
        }

        void publicKey(byte[] publicKey) {
            u8(ED25519_KEY_TYPE);
            bytes(publicKey);
        }

        void bytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        void byteArray(byte[] bytes) {
            u32(bytes.length);
            bytes(bytes);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}

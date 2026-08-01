package com.surprising.wallet.chain.near;

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

/**
 * NEAR 交易签名器，实现 NEAR 交易的 Borsh 序列化和 Ed25519 签名。
 *
 * <p>支持三种 Action 类型：
 * <ul>
 *   <li>Transfer：原生 NEAR 转账</li>
 *   <li>FunctionCall：合约调用（如 ft_transfer）</li>
 *   <li>DeployContract + FunctionCall：部署合约并初始化</li>
 * </ul>
 * 交易字节先 Borsh 序列化，再 SHA-256 哈希，最后 Ed25519 签名。</p>
 */
@Component
public
class NearTransactionSigner {

    /** Ed25519 密钥类型标识 */
    private static final int ED25519_KEY_TYPE = 0;

    /** DeployContract Action 编号 */
    private static final int DEPLOY_CONTRACT_ACTION = 1;

    /** FunctionCall Action 编号 */
    private static final int FUNCTION_CALL_ACTION = 2;

    /** Transfer Action 编号 */
    private static final int TRANSFER_ACTION = 3;

    /** NEAR 密钥服务 */
    private final NearKeyService keyService;
    /**
     * 构造 {@code NearTransactionSigner}，初始化该组件运行所需的状态和依赖。
     */
    public NearTransactionSigner(NearKeyService keyService) {
        this.keyService = keyService;
    }

    /**
     * 执行 {@code transfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code functionCall} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code deployContractAndFunctionCall} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code transactionBytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code functionCallTransactionBytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code deployContractAndFunctionCallTransactionBytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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
    /**
     * 为 {@code signedTransactionBytes} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
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
    /**
     * 转换或计算 {@code sha256} 对应的值，统一金额、格式和边界规则。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
    public record SignedTransaction(String transactionHash, String signedTransactionBase64, String publicKeyBase58) {
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static final class BorshWriter {
        /**
         * 保存 {@code out}，用于承载当前对象的运行配置或业务数据。
         */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * 执行 {@code u8} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void u8(int value) {
            out.write(value & 0xff);
        }

        /**
         * 执行 {@code u32} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void u32(int value) {
            bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }

        /**
         * 执行 {@code u64} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void u64(long value) {
            bytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        /**
         * 执行 {@code u128} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
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

        /**
         * 转换或计算 {@code string} 对应的值，统一金额、格式和边界规则。
         */
        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            u32(bytes.length);
            bytes(bytes);
        }

        /**
         * 执行 {@code publicKey} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void publicKey(byte[] publicKey) {
            u8(ED25519_KEY_TYPE);
            bytes(publicKey);
        }

        /**
         * 执行 {@code bytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void bytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        /**
         * 执行 {@code byteArray} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void byteArray(byte[] bytes) {
            u32(bytes.length);
            bytes(bytes);
        }

        /**
         * 编码 {@code toByteArray} 对应的数据，生成链上或接口所需的表示。
         */
        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}

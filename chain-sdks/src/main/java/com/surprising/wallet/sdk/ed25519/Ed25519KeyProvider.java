package com.surprising.wallet.sdk.ed25519;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class Ed25519KeyProvider {
    /**
     * 定义 {@code MASTER_KEY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] MASTER_KEY = "ed25519 seed".getBytes(StandardCharsets.US_ASCII);
    /**
     * 定义 {@code ED25519} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final EdDSANamedCurveSpec ED25519 =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    /**
     * 保存 {@code masterSeed}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final byte[] masterSeed;

    /**
     * 构造 {@code Ed25519KeyProvider}，初始化该组件运行所需的状态和依赖。
     */
    public Ed25519KeyProvider(byte[] masterSeed) {
        if (masterSeed == null || masterSeed.length < 16) {
            throw new IllegalArgumentException("master seed must contain at least 128 bits");
        }
        this.masterSeed = Arrays.copyOf(masterSeed, masterSeed.length);
    }

    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    public Ed25519DerivedKey derive(Ed25519Chain chain, long userIndex) {
        return derive(chain.pathForUser(userIndex), chain.pathString(userIndex));
    }

    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    public Ed25519DerivedKey derive(Ed25519Chain chain, int biz, long userId, long addressIndex) {
        return derive(chain.pathForAccount(biz, userId, addressIndex),
                chain.pathString(biz, userId, addressIndex));
    }

    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    private Ed25519DerivedKey derive(int[] path, String pathString) {
        byte[] digest = hmacSha512(MASTER_KEY, masterSeed);
        byte[] key = Arrays.copyOfRange(digest, 0, 32);
        byte[] chainCode = Arrays.copyOfRange(digest, 32, 64);
        Arrays.fill(digest, (byte) 0);

        for (int index : path) {
            byte[] data = ByteBuffer.allocate(37)
                    .put((byte) 0)
                    .put(key)
                    .putInt(index | 0x80000000)
                    .array();
            byte[] child = hmacSha512(chainCode, data);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(chainCode, (byte) 0);
            Arrays.fill(data, (byte) 0);
            key = Arrays.copyOfRange(child, 0, 32);
            chainCode = Arrays.copyOfRange(child, 32, 64);
            Arrays.fill(child, (byte) 0);
        }

        EdDSAPrivateKeySpec privateSpec = new EdDSAPrivateKeySpec(key, ED25519);
        byte[] publicKey = privateSpec.getA().toByteArray();
        Ed25519DerivedKey result = new Ed25519DerivedKey(pathString, key, publicKey);
        Arrays.fill(key, (byte) 0);
        Arrays.fill(chainCode, (byte) 0);
        return result;
    }

    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public byte[] sign(Ed25519Chain chain, long userIndex, byte[] message) {
        Ed25519DerivedKey derived = derive(chain, userIndex);
        return sign(derived, message);
    }

    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public byte[] sign(Ed25519Chain chain, int biz, long userId, long addressIndex, byte[] message) {
        Ed25519DerivedKey derived = derive(chain, biz, userId, addressIndex);
        return sign(derived, message);
    }

    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    private byte[] sign(Ed25519DerivedKey derived, byte[] message) {
        EdDSAPrivateKey privateKey = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(derived.privateSeed(), ED25519));
        EdDSAEngine signer = new EdDSAEngine();
        try {
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /**
     * 验证 {@code verify} 对应的签名、交易或数据证明是否有效。
     */
    public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        EdDSAPublicKey key = new EdDSAPublicKey(new EdDSAPublicKeySpec(publicKey, ED25519));
        EdDSAEngine verifier = new EdDSAEngine();
        try {
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 verification failed", e);
        }
    }

    /**
     * 解析或转换 {@code decodeMasterSeed} 对应的数据，并校验其格式和边界。
     */
    public static byte[] decodeMasterSeed(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Ed25519 master seed is required for Ed25519 chains");
        }
        String value = encoded.trim();
        try {
            if (value.matches("(?i)[0-9a-f]+") && value.length() % 2 == 0) {
                return HexFormat.of().parseHex(value);
            }
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("master seed must be hex or base64", e);
        }
    }

    /**
     * 转换或计算 {@code hmacSha512} 对应的值，统一金额、格式和边界规则。
     */
    private static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 unavailable", e);
        }
    }
}

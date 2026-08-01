package com.surprising.wallet.custody.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import com.surprising.wallet.custody.model.CustodySecurityProperties;

/**
 * 托管密码学服务，提供 AES-256-GCM 加密和 HMAC-SHA256 签名。
 *
 * <p>用于保护敏感数据（API Key Secret、Webhook Secret 等）的存储安全。
 * 密钥材料来自环境变量 {@code SW_CUSTODY_SECRET_MASTER_KEY}，应用启动时初始化。
 * 加密后的数据以 Base64 URL-safe 格式存储。
 */
@Service
public class CustodyCryptoService {
    /**
     * 定义 {@code RANDOM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 定义 {@code URL_ENCODER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /**
     * 定义 {@code URL_DECODER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    /**
     * 定义 {@code VERSION} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String VERSION = "v1";
    /**
     * 保存 {@code properties}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodySecurityProperties properties;
    /**
     * 构造 {@code CustodyCryptoService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyCryptoService(CustodySecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验 {@code validateConfiguration} 对应的前置条件，不满足时抛出明确异常。
     */
    @PostConstruct
    public void validateConfiguration() {
        byte[] key = masterKey();
        Arrays.fill(key, (byte) 0);
    }
    /**
     * 执行 {@code randomSecret} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String randomSecret(int bytes) {
        if (bytes < 16) {
            throw new IllegalArgumentException("secret must contain at least 16 random bytes");
        }
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return URL_ENCODER.encodeToString(value);
    }
    /**
     * 执行 {@code encrypt} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("secret is required");
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key = masterKey();
            try {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            cipher.updateAAD(VERSION.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted);
            return VERSION + ":" + URL_ENCODER.encodeToString(payload.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt custody secret", e);
        }
    }
    /**
     * 执行 {@code decrypt} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(VERSION + ":")) {
            throw new IllegalArgumentException("unsupported custody secret format");
        }
        try {
            byte[] payload = URL_DECODER.decode(ciphertext.substring(VERSION.length() + 1));
            if (payload.length < 29) {
                throw new IllegalArgumentException("invalid custody secret payload");
            }
            byte[] nonce = new byte[12];
            byte[] encrypted = new byte[payload.length - nonce.length];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key = masterKey();
            try {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            cipher.updateAAD(VERSION.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to decrypt custody secret", e);
        }
    }
    /**
     * 转换或计算 {@code hmacSha256} 对应的值，统一金额、格式和边界规则。
     */
    public String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign custody message", e);
        }
    }
    /**
     * 转换或计算 {@code sha256} 对应的值，统一金额、格式和边界规则。
     */
    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
    /**
     * 转换或计算 {@code sha256} 对应的值，统一金额、格式和边界规则。
     */
    public String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
    /**
     * 执行 {@code constantTimeEquals} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
    /**
     * 执行 {@code masterKey} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private byte[] masterKey() {
        String configured = properties.getSecretMasterKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("custody.security.secret-master-key must be configured");
        }
        byte[] key;
        try {
            key = configured.matches("^[0-9a-fA-F]{64}$")
                    ? HexFormat.of().parseHex(configured)
                    : Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "custody.security.secret-master-key must be a 32-byte Base64 value or 64 hex characters", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("custody.security.secret-master-key must decode to exactly 32 bytes");
        }
        return key;
    }
}

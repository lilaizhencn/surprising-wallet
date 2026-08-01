package com.surprising.wallet.service;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希服务，使用 PBKDF2-SHA256 对 Console 登录密码进行哈希。
 *
 * <p>格式：pbkdf2-sha256$iterations$salt$hash，每次使用 16 字节随机盐。
 * 未使用 bcrypt/argon2 以保持 Java 标准库兼容。
 */
@Service
public class CustodyPasswordService {
    /**
     * 定义 {@code ALGORITHM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /**
     * 定义 {@code PREFIX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String PREFIX = "pbkdf2-sha256";
    /**
     * 定义 {@code ITERATIONS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int ITERATIONS = 210_000;
    /**
     * 定义 {@code KEY_BITS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int KEY_BITS = 256;
    /**
     * 定义 {@code RANDOM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 判断 {@code hash} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public String hash(String password) {
        validatePassword(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }
    /**
     * 验证 {@code verify} 对应的签名、交易或数据证明是否有效。
     */
    public boolean verify(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    /**
     * 校验 {@code validatePassword} 对应的前置条件，不满足时抛出明确异常。
     */
    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("password must contain between 12 and 256 characters");
        }
    }
    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    private byte[] derive(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}

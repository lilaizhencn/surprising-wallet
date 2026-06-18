package com.surprising.wallet.sdk.bitcoinj.crypto;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要和哈希算法的工具类。
 */
public class DigestHash {

    /**
     * 对输入内容进行hash160加密。
     *
     * @param input
     * @return
     */
    public static byte[] hash160(byte[] input) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[20];
        digest.doFinal(out, 0);
        return out;
    }

    /**
     * 对输入内容进行sha256加密。
     *
     * @param input
     * @return
     */
    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            System.out.println("SHA256加密过程出错。。。");
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 对输入内容进行两次sha256加密。
     *
     * @param input
     * @return
     */
    public static byte[] sha256X2(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            System.out.println("SHA256加密过程出错。。。");
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 对输入内容进行sha512加密。
     *
     * @param input
     * @return
     */
    public static byte[] sha512(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            System.out.println("SHA512加密过程出错。。。");
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 先进行SHA256，之后在做Hash160处理。
     *
     * @param input
     * @return
     */
    public static byte[] sha256hash160(byte[] input) {
        return hash160(sha256(input));
    }
}

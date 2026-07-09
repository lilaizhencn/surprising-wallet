package com.surprising.wallet.signature;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

@Log4j2
public class AES {


    /**
     * 加密
     *
     * @param content 需要加密的内
     *                加密密钥
     * @return byte[] 加密后的字节数组
     */
    public static String encrypt(String content, String key) {
        try {
            if (key == null) {
                System.out.print("Key为空null");
                return null;
            }
            // 判断Key是否为16位
            if (key.length() != 16) {
                System.out.print("Key长度不是16位");
                return null;
            }
            byte[] raw = key.getBytes("utf-8");
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            // "算法/模式/补码方式"
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            byte[] encrypted = cipher.doFinal(content.getBytes("utf-8"));
            // 此处使用BASE64做转码功能，同时能起到2次加密的作用。
            return new Base64().encodeToString(encrypted);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 解密
     *
     * @param content 待解密内容
     *                解密密钥
     * @return byte[]
     */
    public static String decrypt(String content, String key) {
        try {
            // 判断Key是否正确
            if (key == null) {
                System.out.print("Key为空null");
                return null;
            }
            // 判断Key是否为16位
            if (key.length() != 16) {
                System.out.print("Key长度不是16位");
                return null;
            }
            byte[] raw = key.getBytes("utf-8");
            SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec);
            // 先用base64解密
            byte[] encrypted1 = new Base64().decode(content);
            byte[] original = cipher.doFinal(encrypted1);
            String originalString = new String(original, "utf-8");
            return originalString;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
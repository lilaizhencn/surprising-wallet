package com.surprising.common;


import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;

/**
 * @author lilaizhen
 * @date 2017/5/8
 */
@Slf4j
public class MD5Util {
    public static String encrypt(String s) {
        char hexDigits[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        try {
            byte[] btInput = s.getBytes();
            // 获得MD5摘要算法的 MessageDigest 对象
            MessageDigest mdInst = MessageDigest.getInstance("MD5");
            // 使用指定的字节更新摘要
            mdInst.update(btInput);
            // 获得密文
            byte[] md = mdInst.digest();
            // 把密文转换成十六进制的字符串形式
            int j = md.length;
            char str[] = new char[j * 2];
            int k = 0;
            for (byte byte0 : md) {
                str[k++] = hexDigits[byte0 >>> 4 & 0xf];
                str[k++] = hexDigits[byte0 & 0xf];
            }
            return new String(str);
        } catch (Exception e) {
            log.error("生成md5失败", e);
            return null;
        }
    }

    /**
     * 登录密码加密入库方法
     *
     * @param pwd 原始密码
     * @return 加密后的密码
     */
    public static String encryptPwd(String pwd) {
        String afterFormat = null;
        String afterEncrypt = null;
        try {
            String salt = "dig?F*ckDang5PaSsWOrd&%(polarisex0160630).";
            afterFormat = pwd + salt;
            afterEncrypt = encrypt(afterFormat);
        } catch (Exception e) {
        }
        return afterEncrypt;
    }

    public static String encryptFdPwd(String pwd, Long uid) {
        String afterFormat = null;
        String afterEncrypt = null;
        try {
            String salt = "dig?F*ckDa2g5PaSsWOrd&%(polarisexenp0160630).";
            afterFormat = pwd + salt + uid;
            afterEncrypt = encrypt(afterFormat);
        } catch (Exception e) {
        }
        return afterEncrypt;
    }

    public static void main(String[] args) {
        //38a8c612cc70b8a8b1f2fd68fdbca167
        System.out.println(encryptPwd("12345678qqL"));
        System.out.println(encryptFdPwd("12345678qqL", 118048L));
        System.out.println("38a8c612cc70b8a8b1f2fd68fdbca167".equalsIgnoreCase(encryptFdPwd("12345678qqL", 118048L)));
    }
}

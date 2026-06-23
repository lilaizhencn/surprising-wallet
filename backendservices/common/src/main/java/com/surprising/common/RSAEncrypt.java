package com.surprising.common;

import com.vip.vjtools.vjkit.base.type.Pair;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.net.URLEncoder;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @author lilaizhen
 */
public class RSAEncrypt {

    public static void main(String[] args) throws Exception {


//
//        System.out.println(s111.equals(m222));
//        // TODO Auto-generated method stub
//        //字典序列排序
//        Map<String,String> paraMap = new HashMap<>();
//        paraMap.put("sign","BEAF84FFFB8D0A4517202E6322BDC7EF");
//        paraMap.put("api_key","MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKI79SBTDLS5OfYHb799XX0cbdAi8BFRr9XvVCX+t8xEtf4L1CIgRNGrvYxaX1d53OjYNVZCXUT1sgl20FaQhLMCAwEAAQ==");
//        paraMap.put("prdName", "btc");
//        paraMap.put("start", "1");
//        paraMap.put("beginTime","2019-02-20");
//        paraMap.put("endTime","2019-02-26");
//        paraMap.put("size","1");
//        paraMap.put("uid","139984");
//        System.out.println("url====="+url);
//        String s =MD5(url);
//        System.out.println("md5 ======"+s);
//
//        String filepath="E:/tmp/";

        //生成公钥和私钥文件
        RSAEncrypt.genKeyPair();

//        String pubKey ="MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCRb4HxTqfW6xpxp2uu9of+YDrDYK3ZDCgakJumN6dlBpWEemaJYL7C61FpEIhVN8mMC9NTrqQ/JfyH0O0AIkgDIVCxr4TcXFJqCWTfkDV1RlBGOmo9LBL9r9T8cbsJsAahs0X0BYBUgWwymkl6+rqQK3heiI+k6T+DVXl5VTLutwIDAQAB";
//        String priKey="MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAJFvgfFOp9brGnGna672h/5gOsNgrdkMKBqQm6Y3p2UGlYR6ZolgvsLrUWkQiFU3yYwL01OupD8l/IfQ7QAiSAMhULGvhNxcUmoJZN+QNXVGUEY6aj0sEv2v1PxxuwmwBqGzRfQFgFSBbDKaSXr6upAreF6Ij6TpP4NVeXlVMu63AgMBAAECgYAaGNhIZMTZW/ayzkgUbUiZ7MqepIqNmBDaM3i6quHuzu+lhhFANYwFjhRdtgnAwPH5n2WcbooqirZ16JeenogtQf0NBoY1zuFCJ60jIMIQ1mvoYmoUxsPmVjakyQdMqcBfDEE78fHvmGZ0hBUfpFpSWivS6q0oSKolAjh0RXX9AQJBANYnTsWwsoFdvV7V11rLTccPLXg6oWpL5qoz4HxjsgHumyxRs+lU+c+Udn1RgdIi1dQpcmdUb+l1ypgjk/7oNWkCQQCt2rEz8jJztJCbmOXF2IfKjssf0ycO6UlOJC26kFb16HcZ7iadbz02ab1G5yyAJbAkfw0TpV+2l53/6np0Xt8fAkBcfqnFNOZEfcpW9aGIM5sqSOHotdoV4SaFiNaCo0S1FOusnrVIKE2lXIg45EVgD2+vrR8ehhe4DAou398CECVRAkB2ow/ddN882fD8XSAfHJ29eifetcanhEaDzmhuMWVGNbZguYUcVfadlRaWtdZGNHG41gJkb6ua/GfTZXzewIbdAkALkD//2la1LqfYGX/BHi/0dyDAsK+lv1lg3JrQVb1VjLDP3yCcYr8WuSQrMxwv7AEMlMtVuH2cA4b00pGMnVq+";
//        System.out.println("--------------公钥加密私钥解密过程-------------------");
//        String plainText="1234567890";
//        //公钥加密过程
//        byte[] cipherData=RSAEncrypt.encrypt(RSAEncrypt.loadPublicKeyByStr(pubKey),plainText.getBytes());
//        String cipher=java.util.Base64.getEncoder().encodeToString(cipherData);
//        //私钥解密过程
//        byte[] res=RSAEncrypt.decrypt(RSAEncrypt.loadPrivateKeyByStr(priKey), java.util.Base64.getDecoder().decode(cipher));
//        String restr=new String(res);
//        System.out.println("原文："+plainText);
//        System.out.println("加密："+cipher);
//        System.out.println("解密："+restr);
//        System.out.println();
//
//        System.out.println("--------------私钥加密公钥解密过程-------------------");
//        plainText="1234567890";
//        //私钥加密过程
//        cipherData=RSAEncrypt.encrypt(RSAEncrypt.loadPrivateKeyByStr(priKey),plainText.getBytes());
//        cipher=java.util.Base64.getEncoder().encodeToString(cipherData);
//        //公钥解密过程
//        res=RSAEncrypt.decrypt(RSAEncrypt.loadPublicKeyByStr(pubKey), java.util.Base64.getDecoder().decode(cipher));
//        restr=new String(res);
//        System.out.println("原文："+plainText);
//        System.out.println("加密："+cipher);
//        System.out.println("解密："+restr);
//        System.out.println();
//
//        System.out.println("---------------私钥签名过程------------------");
//        String content="ihep_这是用于签名的原始数据";
//        String signstr=RSASignature.sign(content,priKey);
//        System.out.println("签名原串："+content);
//        System.out.println("签名串："+signstr);
//        System.out.println();
//
//        System.out.println("---------------公钥校验签名------------------");
//        System.out.println("签名原串："+content);
//        System.out.println("签名串："+signstr);
//
//        System.out.println("验签结果："+RSASignature.doCheck(content, signstr,pubKey));
//        System.out.println();

    }

    /**
     * 随机生成密钥对
     */
    public static Pair<String, String> genKeyPair() {
        // KeyPairGenerator类用于生成公钥和私钥对，基于RSA算法生成对象
        KeyPairGenerator keyPairGen = null;
        try {
            keyPairGen = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // 初始化密钥对生成器，密钥大小为96-1024位
        keyPairGen.initialize(512, new SecureRandom());
        // 生成一个密钥对，保存在keyPair中
        KeyPair keyPair = keyPairGen.generateKeyPair();
        // 得到私钥
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        // 得到公钥
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        try {
            // 得到公钥字符串
            String publicKeyString = java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());
            // 得到私钥字符串
            String privateKeyString = java.util.Base64.getEncoder().encodeToString(privateKey.getEncoded());
            System.out.println("publicKeyString::::" + publicKeyString);
            System.out.println("privateKeyString::::" + privateKeyString);
            return new Pair<>(publicKeyString, privateKeyString);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 从字符串中加载公钥
     *
     * @param publicKeyStr 公钥数据字符串
     * @throws Exception 加载公钥时产生的异常
     */
    private static RSAPublicKey loadPublicKeyByStr(String publicKeyStr)
            throws Exception {
        try {
            byte[] buffer = java.util.Base64.getDecoder().decode(publicKeyStr);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(buffer);
            return (RSAPublicKey) keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此算法");
        } catch (InvalidKeySpecException e) {
            throw new Exception("公钥非法");
        } catch (NullPointerException e) {
            throw new Exception("公钥数据为空");
        }
    }

    private static RSAPrivateKey loadPrivateKeyByStr(String privateKeyStr)
            throws Exception {
        try {
            byte[] buffer = java.util.Base64.getDecoder().decode(privateKeyStr);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(buffer);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此算法");
        } catch (InvalidKeySpecException e) {
            throw new Exception("私钥非法");
        } catch (NullPointerException e) {
            throw new Exception("私钥数据为空");
        }
    }

    /**
     * 公钥加密过程
     *
     * @param publicKey     公钥
     * @param plainTextData 明文数据
     * @return
     * @throws Exception 加密过程中的异常信息
     */
    private static byte[] encrypt(RSAPublicKey publicKey, byte[] plainTextData)
            throws Exception {
        if (publicKey == null) {
            throw new Exception("加密公钥为空, 请设置");
        }
        Cipher cipher = null;
        try {
            // 使用默认RSA
            cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return cipher.doFinal(plainTextData);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此加密算法");
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e) {
            throw new Exception("加密公钥非法,请检查");
        } catch (IllegalBlockSizeException e) {
            throw new Exception("明文长度非法");
        } catch (BadPaddingException e) {
            throw new Exception("明文数据已损坏");
        }
    }

    /**
     * 私钥加密过程
     *
     * @param privateKey    私钥
     * @param plainTextData 明文数据
     * @return
     * @throws Exception 加密过程中的异常信息
     */
    private static byte[] encrypt(RSAPrivateKey privateKey, byte[] plainTextData)
            throws Exception {
        if (privateKey == null) {
            throw new Exception("加密私钥为空, 请设置");
        }
        Cipher cipher = null;
        try {
            // 使用默认RSA
            cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, privateKey);
            return cipher.doFinal(plainTextData);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此加密算法");
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e) {
            throw new Exception("加密私钥非法,请检查");
        } catch (IllegalBlockSizeException e) {
            throw new Exception("明文长度非法");
        } catch (BadPaddingException e) {
            throw new Exception("明文数据已损坏");
        }
    }

    /**
     * 私钥解密过程
     *
     * @param privateKey 私钥
     * @param cipherData 密文数据
     * @return 明文
     * @throws Exception 解密过程中的异常信息
     */
    public static byte[] decrypt(RSAPrivateKey privateKey, byte[] cipherData)
            throws Exception {
        if (privateKey == null) {
            throw new Exception("解密私钥为空, 请设置");
        }
        Cipher cipher = null;
        try {
            // 使用默认RSA
            cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(cipherData);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此解密算法");
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e) {
            throw new Exception("解密私钥非法,请检查");
        } catch (IllegalBlockSizeException e) {
            throw new Exception("密文长度非法");
        } catch (BadPaddingException e) {
            throw new Exception("密文数据已损坏");
        }
    }

    /**
     * 公钥解密过程
     *
     * @param publicKey  公钥
     * @param cipherData 密文数据
     * @return 明文
     * @throws Exception 解密过程中的异常信息
     */
    public static byte[] decrypt(RSAPublicKey publicKey, byte[] cipherData)
            throws Exception {
        if (publicKey == null) {
            throw new Exception("解密公钥为空, 请设置");
        }
        Cipher cipher = null;
        try {
            // 使用默认RSA
            cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, publicKey);
            return cipher.doFinal(cipherData);
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("无此解密算法");
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e) {
            throw new Exception("解密公钥非法,请检查");
        } catch (IllegalBlockSizeException e) {
            throw new Exception("密文长度非法");
        } catch (BadPaddingException e) {
            throw new Exception("密文数据已损坏");
        }
    }

    /**
     * 方法用途: 对所有传入参数按照字段名的Unicode码从小到大排序（字典序），并且生成url参数串<br>
     * 实现步骤: <br>
     *
     * @param paraMap    要排序的Map对象
     * @param urlEncode  是否需要URLENCODE
     * @param keyToLower 是否需要将Key转换为全小写
     *                   true:key转化成小写，false:不转化
     * @return
     */
    public static String formatUrlMap(Map<String, String> paraMap, String secretKey, boolean urlEncode, boolean keyToLower) {
        String buff;
        try {
            List<Map.Entry<String, String>> params = new ArrayList<>(paraMap.entrySet());
            // 对所有传入参数按照字段名的 ASCII 码从小到大排序（字典序）
            params.sort(Comparator.comparing(o -> (o.getKey())));
            // 构造URL 键值对的格式
            StringBuilder buf = new StringBuilder();
            for (Map.Entry<String, String> item : params) {
                if (StringUtils.isNotBlank(item.getKey()) && !"sign".equals(item.getKey()) && !"secret_key".equals(item.getKey())) {
                    String key = item.getKey();
                    String val = item.getValue();
                    if (urlEncode) {
                        val = URLEncoder.encode(val, "utf-8");
                    }
                    if (keyToLower) {
                        buf.append(key.toLowerCase()).append("=").append(val);
                    } else {
                        buf.append(key).append("=").append(val);
                    }
                    buf.append("&");
                }
            }
            buf.append(secretKey);
            buff = buf.toString();
        } catch (Exception e) {
            return null;
        }
        return buff;
    }

    /**
     * 32位MD5加密的大写字符串
     */
    public static String MD5(String s) {
        char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'A', 'B', 'C', 'D', 'E', 'F'};
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
            e.printStackTrace();
            return null;
        }
    }
}

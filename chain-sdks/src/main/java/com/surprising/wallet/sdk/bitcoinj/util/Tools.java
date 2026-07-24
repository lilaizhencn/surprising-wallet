package com.surprising.wallet.sdk.bitcoinj.util;

import com.surprising.wallet.sdk.bitcoinj.crypto.DigestHash;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bouncycastle.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * 比特币工具类，提供底层加密和编码工具方法。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>Base58Check编码</b>：{@link #byteToString(byte, byte[])}和{@link #byteToString(byte[])}
 *       将数据编码为带双重SHA-256校验和的Base58格式，用于生成比特币地址</li>
 *   <li><b>校验和验证</b>：{@link #check}验证Base58Check编码数据的双重SHA-256校验和</li>
 *   <li><b>HMAC-SHA512</b>：{@link #hmacSha512}用于BIP32分层确定性钱包的密钥派生</li>
 *   <li><b>XOR运算</b>：{@link #xor}字节数组异或，用于密码学操作</li>
 *   <li><b>地址生成</b>：{@link #pubKeyHexToAddress}从压缩公钥hex生成Base58Check地址，
 *       使用SHA-256 + RIPEMD160哈希</li>
 * </ul>
 *
 * <h3>加密算法说明</h3>
 * <ul>
 *   <li><b>SHA-256</b>：比特币广泛使用的256位安全哈希算法，用于地址校验和和交易哈希</li>
 *   <li><b>RIPEMD160</b>：160位哈希算法，与SHA-256组合使用生成比特币地址的PubKeyHash</li>
 *   <li><b>Base58</b>：去除易混淆字符（0/O/I/l）的Base64变体，用于比特币地址和私钥的
 *       可读编码</li>
 *   <li><b>HMAC-SHA512</b>：基于SHA-512的消息认证码，用于BIP32从种子派生主密钥</li>
 * </ul>
 */
public class Tools {

    public static byte[] xor(byte[] arr1, byte[] arr2) {
        if (arr1 == null || arr2 == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        if (arr1.length != arr2.length) {
            return null;
        }
        byte[] result = new byte[arr1.length];
        for (int i = 0; i < arr1.length; i++) {
            result[i] = (byte) (arr1[i] ^ arr2[i]);
        }
        return result;
    }

    public static boolean check(byte[] input) {
        if (input == null || input.length < 5) {
            throw new IllegalArgumentException("input is empty");
        }
        int len = input.length;
        byte[] toCheck = Arrays.copyOfRange(input, 0, len - 4);
        byte[] checkCode = DigestHash.sha256X2(toCheck);
        byte[] checkSum = Arrays.copyOfRange(input, len - 4, len);
        return Arrays.areEqual(checkSum, Arrays.copyOfRange(checkCode, 0, 4));
    }

    public static String byteToString(byte version, byte[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("input is empty");
        }
        byte[] versioned = new byte[1 + input.length];
        versioned[0] = version;
        System.arraycopy(input, 0, versioned, 1, input.length);
        return byteToString(versioned);
    }

    public static String byteToString(byte[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("input is empty");
        }
        byte[] result = new byte[input.length + 4];
        System.arraycopy(input, 0, result, 0, input.length);
        byte[] checkSum = DigestHash.sha256X2(input);
        System.arraycopy(checkSum, 0, result, input.length, 4);
        return Base58.encode(result);
    }

    public static String pubKeyHexToAddress(String pubKeyHex, NetworkParameters params) {
        if (pubKeyHex == null || params == null) {
            throw new IllegalArgumentException("pubkey and network must not be null");
        }
        Pattern pattern = compile("[0-9a-fA-F]{66}");
        Matcher matcher = pattern.matcher(pubKeyHex);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid compressed public key hex");
        }
        byte[] pubKey = ByteUtils.parseHex(pubKeyHex);
        Address address = LegacyAddress.fromPubKeyHash(params, DigestHash.hash160(pubKey));
        return address.toString();
    }

    public static String ecKeyToAddress(ECKey ecKey, NetworkParameters params) {
        if (ecKey == null || params == null) {
            throw new IllegalArgumentException("key and network must not be null");
        }
        return Tools.byteToString((byte) params.getAddressHeader(), ecKey.getPubKeyHash());
    }

    public static byte[] hmacSha512(byte[] data, byte[] keySeed) {
        if (data == null || keySeed == null) {
            throw new IllegalArgumentException("data and key must not be null");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKey key = new SecretKeySpec(keySeed, "HmacSHA512");
            mac.init(key);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA512 failed", e);
        }
    }

    public static byte[] parseHex(String hex) {
        return ByteUtils.parseHex(hex);
    }

    public static String formatHex(byte[] bytes) {
        return ByteUtils.formatHex(bytes);
    }
}

package com.surprising.wallet.chain.sui;

import java.util.HexFormat;
/**
 * Sui 十六进制工具类。
 *
 * <p>提供 Sui 地址的标准化（0x 前缀 + 32 字节填充）、
 * 十六进制编解码和前缀处理等静态工具方法。
 */
final class SuiHex {
    private static final HexFormat HEX = HexFormat.of();
    private SuiHex() {
    }
    static String withPrefix(byte[] bytes) {
        return "0x" + HEX.formatHex(bytes);
    }
    static String normalizeAddress(String address) {
        byte[] bytes = addressBytes(address);
        return withPrefix(bytes);
    }
    static byte[] addressBytes(String address) {
        String value = stripPrefix(address);
        if (value.length() > 64) {
            throw new IllegalArgumentException("Sui address is longer than 32 bytes");
        }
        if (value.length() % 2 != 0) {
            value = "0" + value;
        }
        byte[] raw = value.isBlank() ? new byte[0] : HEX.parseHex(value);
        byte[] result = new byte[32];
        System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
        return result;
    }
    static String stripPrefix(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("hex value is blank");
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }
}

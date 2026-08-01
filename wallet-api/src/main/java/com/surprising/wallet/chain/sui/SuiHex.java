package com.surprising.wallet.chain.sui;

import java.util.HexFormat;
/**
 * Sui 十六进制工具类。
 *
 * <p>提供 Sui 地址的标准化（0x 前缀 + 32 字节填充）、
 * 十六进制编解码和前缀处理等静态工具方法。
 */
final class SuiHex {
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();
    /**
     * 构造 {@code SuiHex}，初始化该组件运行所需的状态和依赖。
     */
    private SuiHex() {
    }
    /**
     * 执行 {@code withPrefix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    static String withPrefix(byte[] bytes) {
        return "0x" + HEX.formatHex(bytes);
    }
    /**
     * 转换或计算 {@code normalizeAddress} 对应的值，统一金额、格式和边界规则。
     */
    static String normalizeAddress(String address) {
        byte[] bytes = addressBytes(address);
        return withPrefix(bytes);
    }
    /**
     * 添加 {@code addressBytes} 对应的业务对象，并更新当前组件的集合或索引。
     */
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
    /**
     * 执行 {@code stripPrefix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    static String stripPrefix(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("hex value is blank");
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }
}

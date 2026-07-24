package com.surprising.wallet.common.utils;

import lombok.extern.log4j.Log4j2;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 以太坊工具类，提供十六进制字符串与数值类型之间的转换方法。
 *
 * <p>该类主要用于处理 EVM 兼容链的 RPC 调用中常见的 hex 编码数据，
 * 支持 BigInteger、long、double 等类型与 0x 前缀十六进制字符串之间的相互转换。</p>
 *
 * <h3>关键方法：</h3>
 * <ul>
 *   <li>{@link #hexToBigInteger(String)} - 将 hex 字符串转换为 BigInteger</li>
 *   <li>{@link #hexToLong(String)} - 将 hex 字符串转换为 long</li>
 *   <li>{@link #longToHex(long)} - 将 long 值转换为 hex 字符串</li>
 *   <li>{@link #doubleToHex(double)} - 将 double 值（以 10^18 为单位）转换为 hex 字符串</li>
 *   <li>{@link #hexToDouble(String)} - 将 hex 字符串转换为 double（除以 10^18）</li>
 * </ul>
 *
 * <p>常量 {@link #PASS_RADIX} 为十六进制基数 16，
 * 常量 {@code PASS_VALUE} 为 10^18（即 1 ETH = 10^18 wei）。</p>
 */
@Log4j2
public class EthereumUtil {
    public static final int PASS_RADIX = 16;
    static final double PASS_VALUE = 1.0E18;

    public static BigInteger hexToBigInteger(String hex) {
        if (hex == null || hex.length() < 2) {
            return new BigInteger("0");
        }
        hex = hex.toLowerCase();
        if (!hex.startsWith("0x")) {
            return new BigInteger("0");
        }
        String longStr = hex.substring(2);
        return new BigInteger(longStr, EthereumUtil.PASS_RADIX);
    }

    public static long hexToLong(String hex) {
        if (hex == null || hex.length() < 2) {
            return 0;
        }
        hex = hex.toLowerCase();
        if (!hex.startsWith("0x")) {
            return 0;
        }
        String longStr = hex.substring(2);
        return Long.parseLong(longStr, EthereumUtil.PASS_RADIX);
    }

    public static String longToHex(long value) {
        return "0x" + Long.toHexString(value);
    }

    public static String doubleToHex(double value) {
        // 转成 hex
        BigDecimal bd1 = new BigDecimal(Double.toString(value));
        BigDecimal bd2 = new BigDecimal(Double.toString(EthereumUtil.PASS_VALUE));
        BigInteger a = bd1.multiply(bd2).toBigInteger();
        return "0x" + a.toString(EthereumUtil.PASS_RADIX);
    }

    public static double hexToDouble(String hex) {
        if (hex == null || hex.length() < 2) {
            return 0;
        }
        hex = hex.toLowerCase();
        if (!hex.startsWith("0x")) {
            return 0;
        }
        String longStr = hex.substring(2);
        BigInteger in = new BigInteger(longStr, EthereumUtil.PASS_RADIX);
        return DoubleUtil.round(in.doubleValue(), 8);
    }

    public static void main(String[] args) {
        System.out.println(hexToDouble("0x0a7a358200"));
    }
}
package com.surprising.wallet.chain.tron;

import org.tron.trident.utils.Base58Check;
import org.tron.trident.utils.Numeric;

import java.util.Locale;

/**
 * 负责链上地址、交易或合约数据的编码、解码和格式校验。
 */
public final class TronAddressCodec {
    /**
     * 定义 {@code MAINNET_PREFIX_HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String MAINNET_PREFIX_HEX = "41";
    /**
     * 定义 {@code HEX_ADDRESS_LENGTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int HEX_ADDRESS_LENGTH = 42;
    /**
     * 定义 {@code TOPIC_LENGTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int TOPIC_LENGTH = 64;
    /**
     * 构造 {@code TronAddressCodec}，初始化该组件运行所需的状态和依赖。
     */
    private TronAddressCodec() {
    }
    /**
     * 将 TRON Base58Check 地址解码为 21 字节十六进制地址，并校验主网前缀。
     */
    public static String base58ToHex(String base58Address) {
        byte[] decoded = Base58Check.base58ToBytes(base58Address);
        if (decoded.length != 21 || decoded[0] != 0x41) {
            throw new IllegalArgumentException("invalid TRON base58 address");
        }
        return Numeric.toHexStringNoPrefix(decoded).toLowerCase(Locale.ROOT);
    }
    /**
     * 将带有 {@code 41} 前缀的 TRON 十六进制地址编码为 Base58Check 地址。
     */
    public static String hexToBase58(String hexAddress) {
        String normalized = normalizeHexAddress(hexAddress);
        return Base58Check.bytesToBase58(Numeric.hexStringToByteArray(normalized));
    }
    /**
     * 清理十六进制地址前缀并校验长度、字符集和 TRON 主网地址前缀。
     */
    public static String normalizeHexAddress(String hexAddress) {
        String clean = Numeric.cleanHexPrefix(hexAddress).toLowerCase(Locale.ROOT);
        if (clean.length() != HEX_ADDRESS_LENGTH || !clean.startsWith(MAINNET_PREFIX_HEX)) {
            throw new IllegalArgumentException("TRON hex address must be 21 bytes and start with 41");
        }
        return clean;
    }
    /**
     * 判断字符串是否为可解码的 TRON Base58Check 地址。
     */
    public static boolean isValidBase58(String address) {
        try {
            base58ToHex(address);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    /**
     * 将 TRC-20 Transfer 事件中的 32 字节地址 topic 转换为 TRON Base58Check 地址。
     */
    public static String topicAddressToBase58(String topic) {
        String clean = Numeric.cleanHexPrefix(topic).toLowerCase(Locale.ROOT);
        if (clean.length() != TOPIC_LENGTH) {
            throw new IllegalArgumentException("TRC20 address topic must be 32 bytes");
        }
        return hexToBase58(MAINNET_PREFIX_HEX + clean.substring(24));
    }
    /**
     * 将 TRON Base58Check 地址转换为合约 ABI 使用的十六进制地址。
     */
    public static String toAbiAddress(String base58Address) {
        return base58ToHex(base58Address);
    }
}

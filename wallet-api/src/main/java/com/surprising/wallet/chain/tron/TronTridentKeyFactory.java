package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.tron.TronWalletApi;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.utils.Numeric;

import java.math.BigInteger;
import java.util.Locale;

/**
 * 负责 TRON 链地址、扫描、资源费用或交易处理。
 */
public final class TronTridentKeyFactory {
    /**
     * 构造 {@code TronTridentKeyFactory}，初始化该组件运行所需的状态和依赖。
     */
    private TronTridentKeyFactory() {    }
    /**
     * 解析 {@code fromBitcoinEcKey} 对应的输入，并转换为当前业务模型。
     */
    public static KeyPair fromBitcoinEcKey(ECKey ecKey) {
        if (!ecKey.hasPrivKey()) {
            throw new IllegalArgumentException("Bitcoin ECKey must contain private key for TRON signing");
        }
        return fromPrivateKeyHex(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }
    /**
     * 解析 {@code fromPrivateKeyHex} 对应的输入，并转换为当前业务模型。
     */
    public static KeyPair fromPrivateKeyHex(String privateKeyHex) {
        return new KeyPair(normalizePrivateKeyHex(privateKeyHex));
    }
    /**
     * 转换或计算 {@code normalizePrivateKeyHex} 对应的值，统一金额、格式和边界规则。
     */
    public static String normalizePrivateKeyHex(String privateKeyHex) {
        String clean = Numeric.cleanHexPrefix(privateKeyHex).toLowerCase(Locale.ROOT);
        if (!clean.matches("[0-9a-f]+") || clean.length() > 64) {
            throw new IllegalArgumentException("TRON private key must be hex and at most 32 bytes");
        }
        BigInteger value = new BigInteger(clean, 16);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("TRON private key must be positive");
        }
        return Numeric.toHexStringNoPrefixZeroPadded(value, 64);
    }
    /**
     * 编码 {@code toBase58Address} 对应的数据，生成链上或接口所需的表示。
     */
    public static String toBase58Address(ECKey ecKey) {
        return fromBitcoinEcKey(ecKey).toBase58CheckAddress();
    }
    /**
     * 编码 {@code toHexAddress} 对应的数据，生成链上或接口所需的表示。
     */
    public static String toHexAddress(ECKey ecKey) {
        return fromBitcoinEcKey(ecKey).toHexAddress().toLowerCase(Locale.ROOT);
    }
    /**
     * 执行 {@code legacyBase58Address} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static String legacyBase58Address(ECKey ecKey) {
        return TronWalletApi.getAddress(ecKey.getPubKey());
    }
}

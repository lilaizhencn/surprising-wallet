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
    private TronTridentKeyFactory() {    }
    public static KeyPair fromBitcoinEcKey(ECKey ecKey) {
        if (!ecKey.hasPrivKey()) {
            throw new IllegalArgumentException("Bitcoin ECKey must contain private key for TRON signing");
        }
        return fromPrivateKeyHex(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }
    public static KeyPair fromPrivateKeyHex(String privateKeyHex) {
        return new KeyPair(normalizePrivateKeyHex(privateKeyHex));
    }
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
    public static String toBase58Address(ECKey ecKey) {
        return fromBitcoinEcKey(ecKey).toBase58CheckAddress();
    }
    public static String toHexAddress(ECKey ecKey) {
        return fromBitcoinEcKey(ecKey).toHexAddress().toLowerCase(Locale.ROOT);
    }
    public static String legacyBase58Address(ECKey ecKey) {
        return TronWalletApi.getAddress(ecKey.getPubKey());
    }
}

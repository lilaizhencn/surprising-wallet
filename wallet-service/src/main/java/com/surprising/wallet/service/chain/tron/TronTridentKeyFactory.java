package com.surprising.wallet.service.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.tron.TronWalletApi;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.utils.Numeric;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Bridges the existing Bitcoin ECKey private-key tree to Trident KeyPair.
 * The wallet keeps one secp256k1 root key hierarchy. TRON must reuse the
 * derived Bitcoin ECKey private scalar instead of introducing another seed or
 * mnemonic, otherwise old addresses and signing services would diverge.
 */
public final class TronTridentKeyFactory {
    private TronTridentKeyFactory() {
    }

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

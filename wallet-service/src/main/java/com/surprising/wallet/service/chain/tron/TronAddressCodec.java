package com.surprising.wallet.service.chain.tron;

import org.tron.trident.utils.Base58Check;
import org.tron.trident.utils.Numeric;

import java.util.Locale;

/**
 * Converts TRON addresses between base58check, 21-byte hex, and TRC20 event topics.
 * TRON account addresses are 21 bytes on-chain: one network byte `0x41` plus the
 * 20-byte account hash. TRC20 log topics store only the last 20 bytes, so scanner
 * code must re-add the `41` prefix before matching platform deposit addresses.
 */
public final class TronAddressCodec {
    public static final String MAINNET_PREFIX_HEX = "41";
    private static final int HEX_ADDRESS_LENGTH = 42;
    private static final int TOPIC_LENGTH = 64;
    private TronAddressCodec() {
    }
    public static String base58ToHex(String base58Address) {
        byte[] decoded = Base58Check.base58ToBytes(base58Address);
        if (decoded.length != 21 || decoded[0] != 0x41) {
            throw new IllegalArgumentException("invalid TRON base58 address");
        }
        return Numeric.toHexStringNoPrefix(decoded).toLowerCase(Locale.ROOT);
    }
    public static String hexToBase58(String hexAddress) {
        String normalized = normalizeHexAddress(hexAddress);
        return Base58Check.bytesToBase58(Numeric.hexStringToByteArray(normalized));
    }
    public static String normalizeHexAddress(String hexAddress) {
        String clean = Numeric.cleanHexPrefix(hexAddress).toLowerCase(Locale.ROOT);
        if (clean.length() != HEX_ADDRESS_LENGTH || !clean.startsWith(MAINNET_PREFIX_HEX)) {
            throw new IllegalArgumentException("TRON hex address must be 21 bytes and start with 41");
        }
        return clean;
    }
    public static boolean isValidBase58(String address) {
        try {
            base58ToHex(address);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    public static String topicAddressToBase58(String topic) {
        String clean = Numeric.cleanHexPrefix(topic).toLowerCase(Locale.ROOT);
        if (clean.length() != TOPIC_LENGTH) {
            throw new IllegalArgumentException("TRC20 address topic must be 32 bytes");
        }
        return hexToBase58(MAINNET_PREFIX_HEX + clean.substring(24));
    }
    public static String toAbiAddress(String base58Address) {
        return base58ToHex(base58Address);
    }
}

package com.surprising.wallet.service.chain.monero;

import org.ethereum.crypto.HashUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Set;

public final class MoneroAddressValidator {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int FULL_BLOCK_SIZE = 8;
    private static final int FULL_ENCODED_BLOCK_SIZE = 11;
    private static final int[] DECODED_BLOCK_SIZES = {0, 0, 1, 2, 0, 3, 4, 5, 0, 6, 7, 8};
    private static final Set<Integer> SUPPORTED_PREFIXES = Set.of(
            18, 19, 42,
            53, 54, 63,
            24, 25, 36);

    private MoneroAddressValidator() {
    }

    public static boolean isValid(String address) {
        String value = address == null ? "" : address.trim();
        if (value.length() != 95 && value.length() != 106) {
            return false;
        }
        byte[] decoded = decode(value);
        if (decoded == null || (decoded.length != 69 && decoded.length != 77)) {
            return false;
        }
        int prefix = decoded[0] & 0xff;
        if (!SUPPORTED_PREFIXES.contains(prefix)) {
            return false;
        }
        byte[] payload = Arrays.copyOf(decoded, decoded.length - 4);
        byte[] expected = Arrays.copyOf(HashUtil.sha3(payload), 4);
        byte[] actual = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        return Arrays.equals(expected, actual);
    }

    private static byte[] decode(String encoded) {
        int fullBlockCount = encoded.length() / FULL_ENCODED_BLOCK_SIZE;
        int lastBlockEncodedSize = encoded.length() % FULL_ENCODED_BLOCK_SIZE;
        if (lastBlockEncodedSize >= DECODED_BLOCK_SIZES.length) {
            return null;
        }
        int lastBlockDecodedSize = DECODED_BLOCK_SIZES[lastBlockEncodedSize];
        if (lastBlockEncodedSize > 0 && lastBlockDecodedSize == 0) {
            return null;
        }
        byte[] decoded = new byte[fullBlockCount * FULL_BLOCK_SIZE + lastBlockDecodedSize];
        int outputOffset = 0;
        for (int i = 0; i < fullBlockCount; i++) {
            if (!decodeBlock(encoded.substring(i * FULL_ENCODED_BLOCK_SIZE,
                    (i + 1) * FULL_ENCODED_BLOCK_SIZE), decoded, outputOffset, FULL_BLOCK_SIZE)) {
                return null;
            }
            outputOffset += FULL_BLOCK_SIZE;
        }
        if (lastBlockEncodedSize > 0
                && !decodeBlock(encoded.substring(fullBlockCount * FULL_ENCODED_BLOCK_SIZE),
                decoded, outputOffset, lastBlockDecodedSize)) {
            return null;
        }
        return decoded;
    }

    private static boolean decodeBlock(String block, byte[] output, int offset, int decodedSize) {
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < block.length(); i++) {
            int digit = ALPHABET.indexOf(block.charAt(i));
            if (digit < 0) {
                return false;
            }
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        if (value.bitLength() > decodedSize * 8) {
            return false;
        }
        byte[] bytes = value.toByteArray();
        int sourceOffset = bytes.length > decodedSize && bytes[0] == 0 ? 1 : 0;
        int sourceLength = bytes.length - sourceOffset;
        if (sourceLength > decodedSize) {
            return false;
        }
        Arrays.fill(output, offset, offset + decodedSize, (byte) 0);
        System.arraycopy(bytes, sourceOffset, output, offset + decodedSize - sourceLength, sourceLength);
        return true;
    }
}

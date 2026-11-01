package com.surprising.wallet.sdk.signing;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/** CryptoNote block-base58 and Keccak checksum over public spend/view keys. No private view or spend material. */
public final class MoneroPublicAddressCodec {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int[] ENCODED = {0, 2, 3, 5, 6, 7, 9, 10, 11};
    private static final BigInteger BASE = BigInteger.valueOf(58);
    private MoneroPublicAddressCodec() { }

    public enum Network {
        MAINNET(18, 19, 42), TESTNET(53, 54, 63), STAGENET(24, 25, 36);
        private final int standard, integrated, subaddress;
        Network(int standard, int integrated, int subaddress) {
            this.standard = standard; this.integrated = integrated; this.subaddress = subaddress;
        }
        public static Network parse(String network) {
            return switch (network == null ? "" : network.toLowerCase(Locale.ROOT)) {
                case "mainnet", "main", "regtest" -> MAINNET;
                case "testnet" -> TESTNET;
                case "stagenet" -> STAGENET;
                default -> throw new IllegalArgumentException("unsupported Monero network");
            };
        }
    }
    public record Address(Network network, boolean subaddress, byte[] publicKeys, byte[] paymentId) {
        public Address { publicKeys = publicKeys.clone(); paymentId = paymentId.clone(); }
        @Override public byte[] publicKeys() { return publicKeys.clone(); }
        @Override public byte[] paymentId() { return paymentId.clone(); }
    }

    public static String encode(byte[] publicKeys, String network, boolean subaddress) {
        var net = Network.parse(network);
        return encode(publicKeys, subaddress ? net.subaddress : net.standard, new byte[0]);
    }
    public static String integrated(byte[] publicKeys, String network, byte[] paymentId) {
        require(paymentId != null && paymentId.length == 8, "Monero integrated payment ID must be eight bytes");
        return encode(publicKeys, Network.parse(network).integrated, paymentId);
    }
    private static String encode(byte[] publicKeys, int prefix, byte[] paymentId) {
        validateKeys(publicKeys);
        byte[] payload = new byte[65 + paymentId.length]; payload[0] = (byte) prefix;
        System.arraycopy(publicKeys, 0, payload, 1, 64);
        System.arraycopy(paymentId, 0, payload, 65, paymentId.length);
        byte[] result = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(new Keccak.Digest256().digest(payload), 0, result, payload.length, 4);
        StringBuilder address = new StringBuilder();
        for (int start = 0; start < result.length; start += 8) {
            int size = Math.min(8, result.length - start);
            BigInteger value = new BigInteger(1, Arrays.copyOfRange(result, start, start + size));
            char[] block = new char[ENCODED[size]]; Arrays.fill(block, '1');
            for (int index = block.length - 1; value.signum() > 0; index--) {
                var quotient = value.divideAndRemainder(BASE); block[index] = ALPHABET.charAt(quotient[1].intValueExact()); value = quotient[0];
            }
            address.append(block);
        }
        return address.toString();
    }

    public static Address decode(String address) {
        require(address != null && (address.length() == 95 || address.length() == 106), "Monero address must have 95 or 106 characters");
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        for (int start = 0; start < address.length(); start += 11) {
            int characters = Math.min(11, address.length() - start), bytes = -1;
            for (int size = 1; size < ENCODED.length; size++) if (ENCODED[size] == characters) bytes = size;
            require(bytes > 0, "invalid Monero base58 block size");
            BigInteger value = BigInteger.ZERO;
            for (int index = start; index < start + characters; index++) {
                int digit = ALPHABET.indexOf(address.charAt(index)); require(digit >= 0, "invalid Monero base58 character");
                value = value.multiply(BASE).add(BigInteger.valueOf(digit));
            }
            require(value.bitLength() <= bytes * 8, "Monero base58 block overflows");
            byte[] block = new byte[bytes], encoded = value.toByteArray();
            int significant = Math.min(bytes, encoded.length);
            System.arraycopy(encoded, encoded.length - significant, block, bytes - significant, significant);
            decoded.writeBytes(block);
        }
        byte[] raw = decoded.toByteArray(), payload = Arrays.copyOf(raw, raw.length - 4);
        require(MessageDigest.isEqual(Arrays.copyOfRange(raw, payload.length, raw.length),
                Arrays.copyOf(new Keccak.Digest256().digest(payload), 4)), "Monero address checksum differs");
        int prefix = Byte.toUnsignedInt(raw[0]);
        for (var network : Network.values()) {
            boolean integrated = prefix == network.integrated;
            if (prefix == network.standard || prefix == network.subaddress || integrated) {
                require(raw.length == (integrated ? 77 : 69), "Monero address type and length differ");
                byte[] publicKeys = Arrays.copyOfRange(raw, 1, 65); validateKeys(publicKeys);
                return new Address(network, prefix == network.subaddress, publicKeys, Arrays.copyOfRange(payload, 65, payload.length));
            }
        }
        throw new IllegalArgumentException("unsupported Monero address network prefix");
    }
    public static boolean isValid(String address, String network) {
        try { return decode(address).network() == Network.parse(network); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static void validateKeys(byte[] publicKeys) {
        require(publicKeys != null && publicKeys.length == 64 && Ed25519.validatePublicKeyFull(publicKeys, 0)
                && Ed25519.validatePublicKeyFull(publicKeys, 32), "Monero requires canonical public spend and view points");
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}

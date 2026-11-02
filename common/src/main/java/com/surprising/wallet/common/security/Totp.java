package com.surprising.wallet.common.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238, Google Authenticator compatible: SHA-1, six digits, 30-second steps. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() { }

    public static String newSecret() {
        byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(32);
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; value.append(ALPHABET.charAt((buffer >>> bits) & 31)); }
        }
        Arrays.fill(bytes, (byte) 0);
        return value.toString();
    }

    public static String uri(String issuer, String account, String secret) {
        decode(secret);
        return "otpauth://totp/" + encode(issuer + ":" + account) + "?secret=" + secret
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    public static OptionalLong verify(String secret, String code, Instant now, long lastAcceptedStep) {
        if (code == null || !code.matches("[0-9]{6}")) return OptionalLong.empty();
        long step = now.getEpochSecond() / 30;
        for (long candidate : new long[]{step, step - 1, step + 1}) {
            if (candidate > lastAcceptedStep && candidate >= 0 && MessageDigest.isEqual(
                    code.getBytes(StandardCharsets.US_ASCII), atStep(secret, candidate).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    public static String atStep(String secret, long step) {
        if (step < 0) throw new IllegalArgumentException("invalid authenticator time step");
        byte[] key = decode(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int value = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("authenticator algorithm unavailable", error);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Z2-7]{32}")) throw new IllegalArgumentException("invalid authenticator secret");
        byte[] bytes = new byte[20];
        int buffer = 0, bits = 0, index = 0;
        for (char c : value.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(c); bits += 5;
            if (bits >= 8) { bits -= 8; bytes[index++] = (byte) (buffer >>> bits); }
        }
        return bytes;
    }
    private static String encode(String value) {
        if (value == null || value.isBlank() || value.length() > 254) throw new IllegalArgumentException("authenticator label required");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

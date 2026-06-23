package com.surprising.wallet.common.key;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Unified SLIP-0010 Ed25519 tree derived directly from the wallet master seed.
 *
 * <p>This provider is intentionally independent from the existing secp256k1
 * BTC/EVM/TRON trees. Callers must inject the master seed from secret storage;
 * no per-chain random seed or private-key conversion is supported.</p>
 */
public final class Ed25519KeyProvider {
    private static final byte[] MASTER_KEY = "ed25519 seed".getBytes(StandardCharsets.US_ASCII);
    private static final EdDSANamedCurveSpec ED25519 =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    private final byte[] masterSeed;

    public Ed25519KeyProvider(byte[] masterSeed) {
        if (masterSeed == null || masterSeed.length < 16) {
            throw new IllegalArgumentException("master seed must contain at least 128 bits");
        }
        this.masterSeed = Arrays.copyOf(masterSeed, masterSeed.length);
    }

    public Ed25519DerivedKey derive(Ed25519Chain chain, long userIndex) {
        byte[] digest = hmacSha512(MASTER_KEY, masterSeed);
        byte[] key = Arrays.copyOfRange(digest, 0, 32);
        byte[] chainCode = Arrays.copyOfRange(digest, 32, 64);
        Arrays.fill(digest, (byte) 0);

        for (int index : chain.pathForUser(userIndex)) {
            byte[] data = ByteBuffer.allocate(37)
                    .put((byte) 0)
                    .put(key)
                    .putInt(index | 0x80000000)
                    .array();
            byte[] child = hmacSha512(chainCode, data);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(chainCode, (byte) 0);
            Arrays.fill(data, (byte) 0);
            key = Arrays.copyOfRange(child, 0, 32);
            chainCode = Arrays.copyOfRange(child, 32, 64);
            Arrays.fill(child, (byte) 0);
        }

        EdDSAPrivateKeySpec privateSpec = new EdDSAPrivateKeySpec(key, ED25519);
        byte[] publicKey = privateSpec.getA().toByteArray();
        Ed25519DerivedKey result = new Ed25519DerivedKey(chain.pathString(userIndex), key, publicKey);
        Arrays.fill(key, (byte) 0);
        Arrays.fill(chainCode, (byte) 0);
        return result;
    }

    public byte[] sign(Ed25519Chain chain, long userIndex, byte[] message) {
        Ed25519DerivedKey derived = derive(chain, userIndex);
        EdDSAPrivateKey privateKey = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(derived.privateSeed(), ED25519));
        EdDSAEngine signer = new EdDSAEngine();
        try {
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        EdDSAPublicKey key = new EdDSAPublicKey(new EdDSAPublicKeySpec(publicKey, ED25519));
        EdDSAEngine verifier = new EdDSAEngine();
        try {
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 verification failed", e);
        }
    }

    public static byte[] decodeMasterSeed(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("ATOMEX_MASTER_SEED is required for Ed25519 chains");
        }
        String value = encoded.trim();
        try {
            if (value.matches("(?i)[0-9a-f]+") && value.length() % 2 == 0) {
                return HexFormat.of().parseHex(value);
            }
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("master seed must be hex or base64", e);
        }
    }

    private static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 unavailable", e);
        }
    }
}

package com.surprising.wallet.sdk.signing;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import org.bitcoinj.crypto.ECKey;
import org.bouncycastle.crypto.ec.CustomNamedCurves;

/** Public-key verification only, independent of the native MPC implementation. */
public final class ThresholdSignatureVerifier {
    private ThresholdSignatureVerifier() { }

    public static boolean ecdsa(byte[] publicKey, byte[] digest, byte[] signature) {
        if (publicKey.length != 33 || digest.length != 32) return false;
        try {
            return ECKey.verify(digest, signature, publicKey);
        } catch (Exception invalid) {
            return false;
        }
    }

    public static boolean ed25519(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey.length != 32 || signature.length != 64) return false;
        try {
            byte[] encoded = new byte[44];
            byte[] header = HexFormat.of().parseHex("302a300506032b6570032100");
            System.arraycopy(header, 0, encoded, 0, header.length);
            System.arraycopy(publicKey, 0, encoded, header.length, publicKey.length);
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception invalid) {
            return false;
        }
    }

    public static boolean bip340(byte[] publicKey, byte[] digest, byte[] signature) {
        if (publicKey.length != 32 || digest.length != 32 || signature.length != 64) return false;
        try {
            var curve = CustomNamedCurves.getByName("secp256k1");
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
            if (r.compareTo(curve.getCurve().getField().getCharacteristic()) >= 0 || s.compareTo(curve.getN()) >= 0) return false;
            byte[] compressed = new byte[33];
            compressed[0] = 2;
            System.arraycopy(publicKey, 0, compressed, 1, 32);
            var point = curve.getCurve().decodePoint(compressed);
            var sha = MessageDigest.getInstance("SHA-256");
            byte[] tag = sha.digest("BIP0340/challenge".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            sha.update(tag);
            sha.update(tag);
            sha.update(signature, 0, 32);
            sha.update(publicKey);
            BigInteger challenge = new BigInteger(1, sha.digest(digest)).mod(curve.getN());
            var reconstructed = curve.getG().multiply(s).subtract(point.multiply(challenge)).normalize();
            return !reconstructed.isInfinity() && !reconstructed.getAffineYCoord().toBigInteger().testBit(0)
                    && reconstructed.getAffineXCoord().toBigInteger().equals(r);
        } catch (Exception invalid) {
            return false;
        }
    }
}

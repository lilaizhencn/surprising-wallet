package com.surprising.wallet.sdk.signing;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.transaction.type.Transaction2930;

/** EVM wire encoding around an external DER signature; never accepts a private key. */
public final class MpcEvmTransactionCodec {
    private MpcEvmTransactionCodec() { }

    public static byte[] unsignedPayload(RawTransaction transaction, long chainId) {
        Objects.requireNonNull(transaction, "EVM transaction is required");
        if (chainId <= 0) throw new IllegalArgumentException("EVM chain ID must be positive");
        if (transaction.getType().isLegacy()) return TransactionEncoder.encode(transaction, chainId);
        if (!(transaction.getType().isEip2930() || transaction.getType().isEip1559() || transaction.getType().isEip7702())
                || !(transaction.getTransaction() instanceof Transaction2930 typed) || typed.getChainId() != chainId) {
            throw new IllegalArgumentException("unsupported EVM type or mismatched chain ID");
        }
        return TransactionEncoder.encode(transaction);
    }

    public static byte[] digest(RawTransaction transaction, long chainId) {
        return Hash.sha3(unsignedPayload(transaction, chainId));
    }

    /** Rebuild only the exact accepted unsigned bytes; signed or noncanonical input is rejected. */
    public static RawTransaction restore(byte[] acceptedPayload, long chainId) {
        if (acceptedPayload == null || acceptedPayload.length == 0 || acceptedPayload.length > 1024 * 1024) {
            throw new IllegalArgumentException("invalid persisted unsigned EVM payload");
        }
        var restored = org.web3j.crypto.TransactionDecoder.decode(org.web3j.utils.Numeric.toHexString(acceptedPayload));
        if (!Arrays.equals(acceptedPayload, unsignedPayload(restored, chainId))) {
            throw new IllegalArgumentException("persisted EVM payload is signed, noncanonical or belongs to another chain");
        }
        return restored;
    }

    public static byte[] assemble(byte[] acceptedPayload, long chainId, byte[] publicKey, byte[] derSignature) {
        return assemble(restore(acceptedPayload, chainId), chainId, publicKey, derSignature);
    }

    public static byte[] assemble(RawTransaction transaction, long chainId, byte[] publicKey, byte[] derSignature) {
        var recoverable = signatureData(digest(transaction, chainId), publicKey, derSignature);
        return TransactionEncoder.encode(transaction, transaction.getType().isLegacy()
                ? TransactionEncoder.createEip155SignatureData(recoverable, chainId) : recoverable);
    }

    /** Independently verify the accepted digest, normalize low-S and recover the joint public key. */
    public static Sign.SignatureData signatureData(byte[] digest, byte[] publicKey, byte[] derSignature) {
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("EVM signing digest must contain 32 bytes");
        if (!ThresholdSignatureVerifier.ecdsa(publicKey, digest, derSignature)) {
            throw new IllegalArgumentException("MPC signature does not match the accepted EVM digest and public key");
        }
        ECKey.ECDSASignature decoded;
        try {
            decoded = ECKey.ECDSASignature.decodeFromDER(derSignature);
        } catch (org.bitcoinj.crypto.SignatureDecodeException invalid) {
            throw new IllegalArgumentException("invalid MPC DER signature", invalid);
        }
        var canonical = new ECDSASignature(decoded.r, decoded.s).toCanonicalised();
        BigInteger publicPoint = Sign.publicFromPoint(ECKey.fromPublicOnly(publicKey).decompress().getPubKey());
        var recoverable = Sign.createSignatureData(canonical, publicPoint, digest);
        int recovery = new BigInteger(1, recoverable.getV()).intValueExact();
        if (recovery != 27 && recovery != 28) throw new IllegalArgumentException("MPC ECDSA recovery ID cannot be encoded by EVM");
        return recoverable;
    }
}

package com.surprising.wallet.sdk.signing.evm7702;

import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;

/** EIP-7702 authorization, operation and outer envelopes around external MPC signatures. */
public final class MpcEvm7702Codec {
    private static final BigInteger MAX_AUTHORITY_NONCE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private MpcEvm7702Codec() { }

    /** Authority is an explicit public binding; chain zero and delegation revocation are not payment intents. */
    public record Authorization(BigInteger chainId, String authority, String delegate, BigInteger nonce) {
        public Authorization {
            Evm7702PayoutItem.requireUint(chainId, "authorization chainId", false);
            authority = Evm7702PayoutItem.requireAddress(authority, "authorization authority", false);
            delegate = Evm7702PayoutItem.requireAddress(delegate, "authorization delegate", false);
            if (nonce == null || nonce.signum() < 0 || nonce.compareTo(MAX_AUTHORITY_NONCE) >= 0) {
                throw new IllegalArgumentException("EIP-7702 authority nonce must be below 2^64 - 1");
            }
        }
    }

    public static byte[] digest(Authorization authorization) {
        return Sign.authorizationHash(authorization.chainId(), authorization.delegate(), authorization.nonce());
    }

    public static AuthorizationTuple authorization(Authorization accepted, byte[] publicKey, byte[] derSignature) {
        requireOwner(accepted.authority(), publicKey);
        var signature = MpcEvmTransactionCodec.signatureData(digest(accepted), publicKey, derSignature);
        return new AuthorizationTuple(accepted.chainId(), accepted.delegate(), accepted.nonce(),
                BigInteger.valueOf((signature.getV()[0] & 0xff) - 27),
                new BigInteger(1, signature.getR()), new BigInteger(1, signature.getS()));
    }

    public static byte[] collectionSignature(BigInteger chainId, Evm7702CollectionRequest accepted,
                                             byte[] publicKey, byte[] derSignature) {
        return operationSignature(new Evm7702OperationSigner().digest(chainId, accepted),
                accepted.authority(), publicKey, derSignature);
    }

    public static byte[] payoutSignature(BigInteger chainId, Evm7702PayoutRequest accepted,
                                         byte[] publicKey, byte[] derSignature) {
        return operationSignature(new Evm7702PayoutSigner().digest(chainId, accepted),
                accepted.authority(), publicKey, derSignature);
    }

    /** Build before reserving the outer SIGN; signed inner authorizations stay in the accepted bytes. */
    public static RawTransaction batch(long chainId, BigInteger nonce, BigInteger priorityFee,
                                       BigInteger maxFee, BigInteger gasLimit, String destination,
                                       String calldata, List<AuthorizationTuple> authorizations) {
        if (chainId <= 0 || nonce == null || nonce.signum() < 0 || nonce.bitLength() > 64
                || nonce.compareTo(MAX_AUTHORITY_NONCE) >= 0 || priorityFee == null || priorityFee.signum() < 0
                || maxFee == null || maxFee.compareTo(priorityFee) < 0 || maxFee.bitLength() > 256
                || gasLimit == null || gasLimit.signum() <= 0 || gasLimit.bitLength() > 64
                || calldata == null || !calldata.matches("0x(?:[0-9a-fA-F]{2})+") || authorizations == null
                || authorizations.size() > 100) {
            throw new IllegalArgumentException("invalid immutable EIP-7702 batch envelope");
        }
        destination = Evm7702PayoutItem.requireAddress(destination, "batch destination", false);
        var authorities = new HashSet<String>();
        for (var authorization : authorizations) {
            if (authorization == null || !BigInteger.valueOf(chainId).equals(authorization.getChainId())) {
                throw new IllegalArgumentException("batch authorization belongs to another chain");
            }
            try {
                String owner = Sign.recoverAuthorizationSigner(authorization);
                new Authorization(authorization.getChainId(), owner, authorization.getAddress(), authorization.getNonce());
                if (!authorities.add(owner.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("duplicate batch authorization authority");
                }
            } catch (java.security.SignatureException invalid) {
                throw new IllegalArgumentException("invalid batch authorization signature", invalid);
            }
        }
        if (authorizations.isEmpty()) {
            return RawTransaction.createTransaction(chainId, nonce, gasLimit, destination,
                    BigInteger.ZERO, calldata, priorityFee, maxFee);
        }
        return RawTransaction.createTransaction(chainId, nonce, priorityFee, maxFee, gasLimit,
                destination, BigInteger.ZERO, calldata, List.of(), List.copyOf(authorizations));
    }

    private static byte[] operationSignature(byte[] digest, String authority, byte[] publicKey, byte[] derSignature) {
        requireOwner(authority, publicKey);
        var signature = MpcEvmTransactionCodec.signatureData(digest, publicKey, derSignature);
        byte[] encoded = new byte[65];
        System.arraycopy(signature.getR(), 0, encoded, 0, 32);
        System.arraycopy(signature.getS(), 0, encoded, 32, 32);
        encoded[64] = signature.getV()[0];
        return encoded;
    }

    private static void requireOwner(String authority, byte[] publicKey) {
        String recovered = "0x" + Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(publicKey).decompress().getPubKey()));
        if (!recovered.equalsIgnoreCase(authority)) {
            throw new IllegalArgumentException("EIP-7702 authority differs from the registered joint public key");
        }
    }
}

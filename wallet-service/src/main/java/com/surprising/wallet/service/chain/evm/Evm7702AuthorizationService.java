package com.surprising.wallet.service.chain.evm;

import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;

import java.math.BigInteger;

/** Produces chain-bound EIP-7702 authorization tuples for an Authority EOA. */
public class Evm7702AuthorizationService {
    public AuthorizationTuple authorize(BigInteger chainId, String delegateAddress,
                                        BigInteger authorityNonce, Credentials authorityCredentials) {
        if (chainId == null || chainId.signum() <= 0 || chainId.bitLength() > 63) {
            throw new IllegalArgumentException("7702 authorization requires a positive exact chainId");
        }
        if (authorityNonce == null || authorityNonce.signum() < 0 || authorityNonce.bitLength() > 256) {
            throw new IllegalArgumentException("authority nonce must be a valid uint256");
        }
        if (authorityCredentials == null) {
            throw new IllegalArgumentException("authority credentials are required");
        }
        Evm7702CollectionRequest addressCheck = new Evm7702CollectionRequest(
                new byte[32], BigInteger.ZERO, authorityCredentials.getAddress(), delegateAddress,
                delegateAddress, delegateAddress, BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ONE, BigInteger.ONE);
        AuthorizationTuple tuple = AuthorizationTuple.from(
                chainId, addressCheck.collector(), authorityNonce, authorityCredentials);
        try {
            if (!Sign.recoverAuthorizationSigner(tuple).equalsIgnoreCase(authorityCredentials.getAddress())) {
                throw new IllegalStateException("authorization signer recovery mismatch");
            }
        } catch (Exception e) {
            throw new IllegalStateException("generated authorization is invalid", e);
        }
        return tuple;
    }
}

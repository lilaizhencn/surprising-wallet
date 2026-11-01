package com.surprising.wallet.chain.evm;

import java.math.BigInteger;

/** Selected external MPC envelope, committed before any fee query or broadcast. */
public record Evm7702SignedBatchTransaction(
        String rawTransaction, String transactionHash, BigInteger relayerNonce, int transactionType) { }

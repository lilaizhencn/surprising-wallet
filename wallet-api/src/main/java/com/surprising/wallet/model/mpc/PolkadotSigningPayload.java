package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact owned payment and native fee ceiling for the selected relay/Asset Hub nonce domain. */
public record PolkadotSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee,
                                     int decimals, int nativeDecimals, BigInteger feeBudget,
                                     BigInteger existentialDeposit, MpcPolkadotTransactionCodec.Unsigned unsigned) { }

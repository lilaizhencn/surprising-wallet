package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact owned payment and native fee ceiling for the selected relay/Asset Hub nonce domain. */
public record PolkadotSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee,
                                     int decimals, int nativeDecimals, BigInteger feeBudget,
                                     BigInteger existentialDeposit, MpcPolkadotTransactionCodec.Unsigned unsigned) { }

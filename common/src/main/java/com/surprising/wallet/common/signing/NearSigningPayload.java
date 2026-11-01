package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import java.math.BigDecimal;

/** Owned exact payment, customer fee and immutable NEAR reference/nonce domain. */
public record NearSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                 MpcNearTransactionCodec.Unsigned unsigned) { }

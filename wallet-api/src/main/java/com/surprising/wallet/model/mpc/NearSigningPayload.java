package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.near.MpcNearTransactionCodec;
import java.math.BigDecimal;

/** Owned exact payment, customer fee and immutable NEAR reference/nonce domain. */
public record NearSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                 MpcNearTransactionCodec.Unsigned unsigned) { }

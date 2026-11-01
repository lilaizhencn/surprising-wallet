package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.sui.MpcSuiTransactionCodec;
import java.math.BigDecimal;

/** Exact tenant payment, customer fee, network identity and fixed Coin object references. */
public record SuiSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee, int decimals,
                                MpcSuiTransactionCodec.Unsigned unsigned) { }

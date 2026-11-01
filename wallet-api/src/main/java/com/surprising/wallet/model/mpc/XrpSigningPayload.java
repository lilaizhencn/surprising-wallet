package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.xrp.MpcXrpTransactionCodec;
import java.math.BigDecimal;

/** Fixed business amount and customer fee accompany the canonical XRPL binary. */
public record XrpSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee,
                                MpcXrpTransactionCodec.Unsigned unsigned) { }

package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.BigDecimal;

/** Fixed business amount and customer fee accompany the canonical XRPL binary. */
public record XrpSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee,
                                MpcXrpTransactionCodec.Unsigned unsigned) { }

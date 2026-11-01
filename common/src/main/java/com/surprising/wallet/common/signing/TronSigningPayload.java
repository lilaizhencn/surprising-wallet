package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import java.math.BigDecimal;

/** Asset identity and precision remain fixed alongside the complete protobuf transaction. */
public record TronSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee, int decimals,
                                 MpcTronTransactionCodec.Unsigned unsigned) { }

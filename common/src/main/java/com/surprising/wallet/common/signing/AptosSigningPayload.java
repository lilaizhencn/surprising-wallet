package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import java.math.BigDecimal;

/** Fixed owned payment, customer fee and canonical Aptos signing domain. */
public record AptosSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                  MpcAptosTransactionCodec.Unsigned unsigned) { }

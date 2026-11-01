package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.aptos.MpcAptosTransactionCodec;
import java.math.BigDecimal;

/** Fixed owned payment, customer fee and canonical Aptos signing domain. */
public record AptosSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                  MpcAptosTransactionCodec.Unsigned unsigned) { }

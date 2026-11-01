package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.ton.MpcTonTransactionCodec;
import java.math.BigDecimal;

/** Original owned business source plus fixed public owner/TEP-74 payment and customer charge. */
public record TonSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                String sourceAddress,MpcTonTransactionCodec.Unsigned unsigned) { }

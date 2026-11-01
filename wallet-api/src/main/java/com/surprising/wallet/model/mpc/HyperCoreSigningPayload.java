package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.hypercore.MpcHyperCoreActionCodec;
import java.math.BigDecimal;

public record HyperCoreSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,
                                     MpcHyperCoreActionCodec.Unsigned unsigned) { }

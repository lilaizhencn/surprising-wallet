package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;
import java.math.BigDecimal;

public record HyperCoreSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,
                                     MpcHyperCoreActionCodec.Unsigned unsigned) { }

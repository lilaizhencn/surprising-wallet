package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.common.chain.ChainScanPoint;
import java.math.BigDecimal;

/** Accepted business amount, confirmation policy and the immutable public payment snapshot. */
public record CardanoSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee, int decimals,
                                    int requiredConfirmations, ChainScanPoint observed,
                                    MpcCardanoTransactionCodec.Unsigned unsigned) { }

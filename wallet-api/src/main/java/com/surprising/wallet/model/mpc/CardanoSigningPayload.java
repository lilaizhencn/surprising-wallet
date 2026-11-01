package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.chain.model.ChainScanPoint;
import java.math.BigDecimal;

/** Accepted business amount, confirmation policy and the immutable public payment snapshot. */
public record CardanoSigningPayload(String symbol, BigDecimal amount, BigDecimal customerFee, int decimals,
                                    int requiredConfirmations, ChainScanPoint observed,
                                    MpcCardanoTransactionCodec.Unsigned unsigned) { }

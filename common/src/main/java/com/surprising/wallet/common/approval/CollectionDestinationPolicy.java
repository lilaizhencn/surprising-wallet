package com.surprising.wallet.common.approval;

import java.math.BigDecimal;
import java.util.UUID;

/** Read-only independent operating policy identity; this is not a signing grant. */
public record CollectionDestinationPolicy(UUID tenantId, String chain, String network,
                                          String assetSymbol, UUID destinationKeyId,
                                          String destinationAddress, UUID ruleId,
                                          String ruleFingerprint, BigDecimal maximumAmount,
                                          BigDecimal maximum24hAmount) { }

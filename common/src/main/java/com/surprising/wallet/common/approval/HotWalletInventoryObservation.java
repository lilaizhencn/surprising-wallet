package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Independently observed on-chain balance, denominated in exact atomic units. */
public record HotWalletInventoryObservation(UUID tenantId, UUID keyId, String chain,
                                            String network, String networkIdentity, String assetSymbol,
                                            String address, int decimals, int nativeDecimals,
                                            BigInteger availableAtomic, BigInteger nativeAtomic,
                                            Instant observedAt, UUID collectionRuleId,
                                            String collectionRuleFingerprint,
                                            Long observedBlockHeight, String observedBlockHash) { }

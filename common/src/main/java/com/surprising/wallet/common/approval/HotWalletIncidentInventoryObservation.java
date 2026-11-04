package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Read-only independent balance evidence for a quarantined key; never a signing grant. */
public record HotWalletIncidentInventoryObservation(UUID tenantId, UUID keyId, String chain,
        String network, String networkIdentity, String assetSymbol, String tokenIdentifier, String address,
        int decimals, int nativeDecimals, BigInteger availableAtomic, BigInteger nativeAtomic,
        Instant observedAt, String keyState, String quarantineHash,
        String accountCodeState, String delegatedTo, String observedBlockHash) { }

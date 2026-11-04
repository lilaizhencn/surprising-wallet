package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Independent proof of a finalized direct EVM payment and a canonical balance at or after its block. */
public record HotWalletSettledSpendObservation(UUID tenantId, UUID keyId, String chain,
        String network, String networkIdentity, String assetSymbol, String tokenIdentifier,
        String sourceAddress, String recipient, String txHash, BigInteger amountAtomic,
        int decimals, int nativeDecimals, BigInteger availableAtomic, BigInteger nativeAtomic,
        long receiptBlockHeight, String receiptBlockHash, long observedBlockHeight,
        String observedBlockHash, Instant observedAt, UUID collectionRuleId,
        String collectionRuleFingerprint) { }

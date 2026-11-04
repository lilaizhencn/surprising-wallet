package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Finalized direct payment plus a canonical receiving-wallet balance at or after the receipt. */
public record HotWalletSettledCollectionObservation(UUID tenantId,UUID sourceKeyId,
        UUID destinationKeyId,String chain,String network,String networkIdentity,
        String assetSymbol,String tokenIdentifier,String sourceAddress,String destinationAddress,
        String txHash,BigInteger amountAtomic,int decimals,int nativeDecimals,
        BigInteger availableAtomic,BigInteger nativeAtomic,long receiptBlockHeight,
        String receiptBlockHash,long observedBlockHeight,String observedBlockHash,
        Instant observedAt,UUID collectionRuleId,String collectionRuleFingerprint) { }

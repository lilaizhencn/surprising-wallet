package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.util.UUID;

/** Original direct EVM collection claimed for independent finalized-chain accounting. */
public record HotWalletSettledCollectionRequest(UUID tenantId,String chain,String network,
        String assetSymbol,UUID sourceKeyId,String sourceAddress,UUID destinationKeyId,
        String destinationAddress,String txHash,BigInteger amountAtomic) { }

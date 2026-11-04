package com.surprising.wallet.common.approval;

import java.math.BigInteger;
import java.util.UUID;

/** Exact original direct EVM withdrawal claimed for independent finalized-chain reconciliation. */
public record HotWalletSettledSpendRequest(UUID tenantId, String chain, String network,
                                           String assetSymbol, UUID keyId, String txHash,
                                           String recipient, BigInteger amountAtomic) { }

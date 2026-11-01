package com.surprising.wallet.chain.model;

import java.util.UUID;

/** Wallet-owned output history identifies internal inputs after the node has consumed their UTxOs. */
public record CardanoOwnedOutput(String txHash, int outputIndex, UUID tenantId, long addressId,
                                 String address, long blockHeight, String blockHash, String outputCbor) { }

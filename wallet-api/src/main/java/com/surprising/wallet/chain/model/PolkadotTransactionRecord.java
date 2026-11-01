package com.surprising.wallet.chain.model;

import java.math.BigInteger;
import java.util.UUID;

/** One immutable request owns the pending nonce before a witness-dependent extrinsic hash exists. */
public record PolkadotTransactionRecord(UUID requestId, UUID tenantId, String role, String sender,
                                        String txHash, String status, String rawPayload,
                                        BigInteger feePlanck, int nativeDecimals, Long blockHeight,
                                        String executionReceipt) { }

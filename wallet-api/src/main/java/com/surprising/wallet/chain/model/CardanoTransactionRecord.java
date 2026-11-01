package com.surprising.wallet.chain.model;

import com.surprising.wallet.common.chain.ChainScanPoint;

import java.math.BigInteger;
import java.util.UUID;

/** The immutable body hash is known before MPC; the selected witness and final proof are durable separately. */
public record CardanoTransactionRecord(UUID requestId, UUID tenantId, String sender, String txHash, String status,
                                       String rawPayload, ChainScanPoint observed, ChainScanPoint searched,
                                       BigInteger feeLovelace, BigInteger nativeExpenseLovelace,
                                       Long blockHeight, String executionReceipt) { }

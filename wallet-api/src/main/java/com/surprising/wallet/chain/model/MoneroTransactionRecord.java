package com.surprising.wallet.chain.model;

import java.math.BigInteger;
import java.util.UUID;

/** Tenant-owned original native signing request, public selection and canonical confirmation. */
public record MoneroTransactionRecord(UUID tenantId,UUID requestId,String sender,String txHash,UUID selectedAttemptId,
        String artifactHash,BigInteger feeAtomic,String status,Long blockHeight,String blockHash,int confirmations,String executionReceipt) { }

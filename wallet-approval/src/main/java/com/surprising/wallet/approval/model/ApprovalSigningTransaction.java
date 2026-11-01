package com.surprising.wallet.approval.model;

import java.time.Instant;
import java.util.UUID;

public record ApprovalSigningTransaction(UUID id,UUID tenantId,String chain,String network,String businessType,
        String businessReference,String spendDomain,String businessHash,Instant createdAt) { }

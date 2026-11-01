package com.surprising.wallet.approval.model;

import com.surprising.wallet.common.approval.SigningCandidate;
import java.util.UUID;

public record ApprovalSigningSlot(UUID id,UUID tenantId,UUID versionId,String candidateHash,SigningCandidate candidate) { }

package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;

import com.surprising.wallet.common.approval.WithdrawalIntent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalRequest(WithdrawalIntent intent, String intentHash, ApprovalAssetSnapshot assetSnapshot, UUID policyId, long policyVersion,
        List<String> reasons, int requiredReviewers, String status, boolean signingStarted,
        Instant createdAt, Instant expiresAt) {
    public ApprovalRequest { reasons = List.copyOf(reasons); }
}

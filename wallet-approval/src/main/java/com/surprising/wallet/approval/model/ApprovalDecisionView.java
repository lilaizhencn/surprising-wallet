package com.surprising.wallet.approval.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable decision history shown only to active members of the owning tenant. */
public record ApprovalDecisionView(UUID reviewerId,String decision,String reason,Instant createdAt) { }

package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.approval.SigningAuthorization;
import java.time.Instant;
import java.util.UUID;

public record SigningGrant(UUID id,UUID tenantId,UUID slotId,UUID sessionId,
        SigningAuthorization.Proof proof,Instant issuedAt,Instant expiresAt) { }

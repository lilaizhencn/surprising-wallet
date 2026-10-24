package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record TenantUserRecord(
        UUID id,
        UUID tenantId,
        String email,
        String displayName,
        String passwordHash,
        String role,
        String status,
        int failedLoginCount,
        Instant lockedUntil
) {
}

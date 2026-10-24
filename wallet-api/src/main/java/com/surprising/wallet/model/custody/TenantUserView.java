package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record TenantUserView(
        UUID id,
        String email,
        String displayName,
        String role,
        String status,
        int failedLoginCount,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {
}

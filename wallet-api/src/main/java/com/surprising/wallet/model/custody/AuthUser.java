package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

/**
 * 认证用户记录。
 */
public record AuthUser(
        UUID id,
        UUID tenantId,
        String tenantSlug,
        String tenantStatus,
        String email,
        String displayName,
        String passwordHash,
        String role,
        String status,
        int failedLoginCount,
        Instant lockedUntil
) {
}

package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话记录。
 */
public record SessionRecord(
        UUID sessionId,
        UUID userId,
        UUID tenantId,
        String tenantSlug,
        String email,
        String displayName,
        String role,
        String userStatus,
        String tenantStatus,
        Instant expiresAt
) {
}

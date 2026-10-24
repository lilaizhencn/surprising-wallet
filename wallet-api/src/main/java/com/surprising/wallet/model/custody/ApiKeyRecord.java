package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

/**
 * API 密钥记录。
 */
public record ApiKeyRecord(
        UUID id,
        UUID tenantId,
        String tenantSlug,
        String tenantStatus,
        boolean ipAllowlistEnabled,
        String keyId,
        String name,
        String secretCiphertext,
        String status,
        Instant expiresAt,
        Instant createdAt
) {
}

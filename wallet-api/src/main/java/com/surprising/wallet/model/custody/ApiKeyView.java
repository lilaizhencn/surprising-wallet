package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyView(
        UUID id,
        String keyId,
        String name,
        String status,
        Instant lastUsedAt,
        String lastUsedIp,
        Instant expiresAt,
        Instant createdAt,
        Instant revokedAt
) {
}

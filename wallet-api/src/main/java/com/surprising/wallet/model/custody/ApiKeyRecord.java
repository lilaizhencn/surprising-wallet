package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyRecord(
        UUID id,
        UUID tenantId,
        String keyId,
        String name,
        String secretCiphertext,
        String status,
        Instant expiresAt,
        Instant createdAt
) {
}

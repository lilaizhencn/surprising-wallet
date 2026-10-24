package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record SessionTokenRecord(
        UUID id,
        UUID userId,
        UUID tenantId,
        Instant expiresAt
) {
}

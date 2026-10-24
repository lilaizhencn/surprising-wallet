package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

/**
 * 租户记录。
 */
public record TenantRecord(
        UUID id,
        String slug,
        String name,
        String status,
        int derivationNamespace,
        boolean ipAllowlistEnabled,
        String displayCurrency,
        Instant createdAt,
        Instant updatedAt
) {
}

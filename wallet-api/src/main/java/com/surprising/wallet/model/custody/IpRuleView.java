package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record IpRuleView(
        UUID id,
        String label,
        String cidr,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}

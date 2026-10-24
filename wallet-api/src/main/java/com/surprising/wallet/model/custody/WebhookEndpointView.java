package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record WebhookEndpointView(
        UUID id,
        UUID tenantId,
        String name,
        String url,
        String status,
        Instant verifiedAt,
        Instant lastDeliveryAt,
        Instant createdAt,
        Instant updatedAt
) {
}

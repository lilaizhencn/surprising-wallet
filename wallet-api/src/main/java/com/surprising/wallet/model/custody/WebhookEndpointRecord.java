package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook 端点记录。
 */
public record WebhookEndpointRecord(
        UUID id,
        UUID tenantId,
        String name,
        String url,
        String secretCiphertext,
        String status,
        String verificationTokenHash,
        Instant verifiedAt,
        Instant lastDeliveryAt,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryRecord(
        UUID id,
        UUID tenantId,
        UUID endpointId,
        UUID eventId,
        String status,
        int attemptCount,
        int totalAttemptCount,
        int manualRetryCount,
        Instant nextAttemptAt,
        String nextAttemptTrigger,
        String lockedBy,
        Instant lockedAt,
        Integer lastHttpStatus,
        String lastError,
        String lastResponse,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
}

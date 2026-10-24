package com.surprising.wallet.model.custody;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryAttemptView(
        UUID id,
        UUID tenantId,
        UUID deliveryId,
        int attemptNumber,
        int retryCycle,
        String trigger,
        String status,
        String workerId,
        Integer httpStatus,
        String errorMessage,
        String responseBody,
        Instant nextAttemptAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMs
) {
}

package com.surprising.wallet.model.custody;

import java.util.UUID;

public record WebhookDeliveryClaim(
        UUID id,
        UUID tenantId,
        UUID endpointId,
        UUID eventId,
        int attemptCount,
        int totalAttemptCount,
        int manualRetryCount,
        String attemptTrigger
) {
}

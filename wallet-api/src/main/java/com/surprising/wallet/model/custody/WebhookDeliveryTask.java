package com.surprising.wallet.model.custody;

import java.util.UUID;

/**
 * Webhook 投递任务。
 */
public record WebhookDeliveryTask(
        UUID id,
        UUID tenantId,
        UUID endpointId,
        UUID eventId,
        int attemptCount,
        int totalAttemptCount,
        int manualRetryCount,
        String attemptTrigger,
        UUID attemptId,
        String workerId,
        String url,
        String secretCiphertext,
        String eventType,
        String payload
) {
}

package com.surprising.wallet.jobs.custody;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyWebhookRetryPolicyTest {
    private final CustodyWebhookRetryPolicy policy = new CustodyWebhookRetryPolicy();
    private final UUID deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private final Instant now = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void automaticRetryUsesBoundedExponentialBackoffWithJitter() {
        var first = policy.decide(deliveryId, 1, null, now);
        var eighth = policy.decide(deliveryId, 8, null, now);

        assertFalse(first.terminal());
        assertTrue(first.delay().compareTo(Duration.ofSeconds(30)) >= 0);
        assertTrue(first.delay().compareTo(Duration.ofSeconds(36)) <= 0);
        assertFalse(eighth.terminal());
        assertTrue(eighth.delay().compareTo(Duration.ofHours(6)) <= 0);
    }

    @Test
    void retryAfterIsHonoredButCapped() {
        var decision = policy.decide(deliveryId, 1, "999999", now);

        assertEquals(Duration.ofHours(6), decision.delay());
        assertEquals(now.plus(Duration.ofHours(6)), decision.nextAttemptAt());
    }

    @Test
    void tenthFailureIsTerminalUntilManualRetry() {
        var decision = policy.decide(
                deliveryId, CustodyWebhookRetryPolicy.MAX_AUTOMATIC_ATTEMPTS, null, now);

        assertTrue(decision.terminal());
        assertNull(decision.nextAttemptAt());
        assertEquals(Duration.ZERO, decision.delay());
    }
}

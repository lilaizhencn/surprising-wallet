package com.surprising.wallet.custody.model;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Component
public
class CustodyWebhookRetryPolicy {
    public static final int MAX_AUTOMATIC_ATTEMPTS = 10;
    private static final Duration MIN_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofHours(6);

    public RetryDecision decide(
            UUID deliveryId, int attemptCount, String retryAfter, Instant now) {
        if (attemptCount >= MAX_AUTOMATIC_ATTEMPTS) {
            return new RetryDecision(true, null, Duration.ZERO);
        }
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 10);
        long baseSeconds = Math.min(
                MAX_DELAY.toSeconds(),
                MIN_DELAY.toSeconds() * (1L << exponent));
        long jitterPercent = Math.floorMod(
                deliveryId.hashCode() + attemptCount * 31, 21);
        Duration delay = Duration.ofSeconds(
                Math.min(MAX_DELAY.toSeconds(),
                        baseSeconds + (baseSeconds * jitterPercent / 100L)));
        Duration requested = parseRetryAfter(retryAfter, now);
        if (requested != null && requested.compareTo(delay) > 0) {
            delay = requested.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : requested;
        }
        return new RetryDecision(false, now.plus(delay), delay);
    }
    private static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            return Duration.ofSeconds(Math.max(seconds, 0L));
        } catch (NumberFormatException ignored) {
            try {
                Instant requested = ZonedDateTime.parse(
                        normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return requested.isAfter(now)
                        ? Duration.between(now, requested)
                        : Duration.ZERO;
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    public record RetryDecision(
            boolean terminal,
            Instant nextAttemptAt,
            Duration delay
    ) {
    }
}

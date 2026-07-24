package com.surprising.wallet.custody.model;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Webhook 投递重试策略，使用指数退避 + 随机抖动（jitter）计算重试延迟。
 *
 * <p>重试规则：
 * <ul>
 *   <li>最大自动重试次数：{@value #MAX_AUTOMATIC_ATTEMPTS} 次</li>
 *   <li>初始延迟 30 秒，每次翻倍至最大 6 小时</li>
 *   <li>在指数退避基础上叠加 deliveryId + attemptCount 的模 20% 抖动，避免惊群效应</li>
 *   <li>如果目标服务器返回 Retry-After 头且延迟大于计算值，则取较大值（上限 6 小时）</li>
 *   <li>超过最大次数后标记为 terminal，不再自动重试</li>
 * </ul>
 *
 * @see com.surprising.wallet.custody.service.CustodyWebhookService
 */
@Component
public
class CustodyWebhookRetryPolicy {
    /** 最大自动重试次数 */
    public static final int MAX_AUTOMATIC_ATTEMPTS = 10;
    /** 最小重试延迟：30 秒 */
    private static final Duration MIN_DELAY = Duration.ofSeconds(30);
    /** 最大重试延迟：6 小时 */
    private static final Duration MAX_DELAY = Duration.ofHours(6);

    /**
     * 根据投递记录计算下次重试时间。
     *
     * <p>算法：以 30 秒为基数指数退避（翻倍），叠加入参 deliveryId 和 attemptCount 的模 20% 抖动。
     * 如果目标服务器返回 Retry-After 头且大于计算值，取较大值（上限 6 小时）。
     *
     * @param deliveryId   Webhook 投递 ID（用于确定性抖动）
     * @param attemptCount 已尝试次数（含当前）
     * @param retryAfter   服务器返回的 Retry-After 头值（可为 null）
     * @param now          当前时间
     * @return 重试决策（是否终止、下次重试时间、延迟）
     */
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

    /**
     * 重试决策结果。
     *
     * @param terminal     是否标记为最终失败，不再自动重试
     * @param nextAttemptAt 下次重试时间（terminal=true 时为 null）
     * @param delay         距离下次重试的延迟
     */
    public record RetryDecision(
            boolean terminal,
            Instant nextAttemptAt,
            Duration delay
    ) {
    }
}

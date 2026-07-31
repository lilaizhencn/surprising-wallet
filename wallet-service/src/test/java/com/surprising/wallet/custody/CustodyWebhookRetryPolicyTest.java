package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.model.CustodyWebhookRetryPolicy;

/**
 * 验证 {@code CustodyWebhookRetryPolicyTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyWebhookRetryPolicyTest {
    /**
     * 保存 {@code policy}，用于承载当前测试夹具的配置或运行数据。
     */
    private final CustodyWebhookRetryPolicy policy = new CustodyWebhookRetryPolicy();
    /**
     * 保存 {@code deliveryId}，用于标识测试中的交易、区块或业务记录。
     */
    private final UUID deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000123");
    /**
     * 保存 {@code now}，用于承载当前测试夹具的配置或运行数据。
     */
    private final Instant now = Instant.parse("2026-07-20T00:00:00Z");

    /**
     * 验证 {@code automaticRetryUsesBoundedExponentialBackoffWithJitter} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code retryAfterIsHonoredButCapped} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void retryAfterIsHonoredButCapped() {
        var decision = policy.decide(deliveryId, 1, "999999", now);

        assertEquals(Duration.ofHours(6), decision.delay());
        assertEquals(now.plus(Duration.ofHours(6)), decision.nextAttemptAt());
    }

    /**
     * 验证 {@code tenthFailureIsTerminalUntilManualRetry} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tenthFailureIsTerminalUntilManualRetry() {
        var decision = policy.decide(
                deliveryId, CustodyWebhookRetryPolicy.MAX_AUTOMATIC_ATTEMPTS, null, now);

        assertTrue(decision.terminal());
        assertNull(decision.nextAttemptAt());
        assertEquals(Duration.ZERO, decision.delay());
    }
}

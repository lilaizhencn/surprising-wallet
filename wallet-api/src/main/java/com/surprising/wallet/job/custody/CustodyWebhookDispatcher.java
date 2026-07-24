package com.surprising.wallet.job.custody;

import com.surprising.wallet.custody.repository.CustodyRepository.WebhookDeliveryTask;
import com.surprising.wallet.custody.service.CustodyWebhookService.WebhookHttpResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.custody.service.CustodyCryptoService;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodyWebhookRetryPolicy;
import com.surprising.wallet.custody.service.CustodyWebhookService;

/**
 * 托管 Webhook 派发任务。
 * <p>
 * 每 1 秒执行一次：从 DB 拉取待发送的 webhook delivery，
 * 逐条 HTTP POST 到租户配置的回调 URL。失败时根据重试策略计算
 * 下次重试时间，达到上限后标记为最终失败。
 *
 * @author atomex
 */
@Slf4j
@Component
public class CustodyWebhookDispatcher {
    /** 仓储：负责领取/标记 webhook 投递任务。 */
    private final CustodyRepository repository;
    /** 加解密服务，处理 webhook 签名密钥。 */
    private final CustodyCryptoService crypto;
    /** webhook 调用服务。 */
    private final CustodyWebhookService webhooks;
    /** 重试策略。 */
    private final CustodyWebhookRetryPolicy retryPolicy;
    /** 实例唯一 Worker 标识，避免重复领任务。 */
    private final String workerId = "webhook-" + UUID.randomUUID();
    /** 防并发开关。 */
    private final AtomicBoolean running = new AtomicBoolean();

    public CustodyWebhookDispatcher(CustodyRepository repository,
                                    CustodyCryptoService crypto,
                                    CustodyWebhookService webhooks,
                                    CustodyWebhookRetryPolicy retryPolicy) {
        this.repository = repository;
        this.crypto = crypto;
        this.webhooks = webhooks;
        this.retryPolicy = retryPolicy;
    }

    /**
     * 每秒拉取一批待投递任务并异步执行 HTTP 回调。
     */
    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${sw.wallet.custody.webhook-dispatch-delay:1000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<WebhookDeliveryTask> tasks = repository.claimWebhookDeliveries(workerId, 25);
            for (WebhookDeliveryTask task : tasks) {
                deliver(task);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 对单个任务执行发送，成功写 delivered，失败按策略重试。
     */
    private void deliver(WebhookDeliveryTask task) {
        long startedAt = System.nanoTime();
        try {
            String secret = crypto.decrypt(task.secretCiphertext());
            WebhookHttpResult response = webhooks.send(
                    task.url(), secret, task.eventId(), task.eventType(), task.payload());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                repository.markWebhookDelivered(
                        task, response.statusCode(), response.body(), elapsedMs(startedAt));
                return;
            }
            fail(task, response.statusCode(),
                    "webhook returned HTTP " + response.statusCode(),
                    response.body(), response.retryAfter(), elapsedMs(startedAt));
        } catch (RuntimeException e) {
            log.warn("Custody webhook delivery {} failed: {}", task.id(), e.getMessage());
            fail(task, null, e.getMessage(), null, null, elapsedMs(startedAt));
        }
    }

    /**
     * 统一处理发送失败分支，写失败次数与下一次重试时间。
     */
    private void fail(WebhookDeliveryTask task, Integer httpStatus, String error,
                      String response, String retryAfter, long durationMs) {
        CustodyWebhookRetryPolicy.RetryDecision decision = retryPolicy.decide(
                task.id(), task.attemptCount(), retryAfter, java.time.Instant.now());
        repository.markWebhookFailed(
                task, httpStatus, error, response, decision.nextAttemptAt(),
                decision.terminal(), durationMs);
    }

    /**
     * 计算任务执行耗时（毫秒）。
     */
    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}

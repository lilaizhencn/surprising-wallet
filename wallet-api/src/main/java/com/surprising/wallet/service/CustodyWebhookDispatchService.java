package com.surprising.wallet.service;

import com.surprising.wallet.model.CustodyWebhookRetryPolicy;
import com.surprising.wallet.repository.CustodyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 托管 Webhook 投递服务，负责领取投递任务、发送 HTTP 请求和处理重试状态。
 */
@Slf4j
@Service
public class CustodyWebhookDispatchService {
    /** 托管数据仓储。 */
    private final CustodyRepository repository;
    /** Webhook 签名密钥服务。 */
    private final CustodyCryptoService crypto;
    /** Webhook HTTP 调用服务。 */
    private final CustodyWebhookService webhooks;
    /** Webhook 重试策略。 */
    private final CustodyWebhookRetryPolicy retryPolicy;
    /** Webhook 并发执行器。 */
    private final TaskExecutor deliveryExecutor;
    /** 当前进程的 Worker 标识。 */
    private final String workerId = "webhook-" + UUID.randomUUID();

    /** 构造 Webhook 投递服务。 */
    public CustodyWebhookDispatchService(
            CustodyRepository repository,
            CustodyCryptoService crypto,
            CustodyWebhookService webhooks,
            CustodyWebhookRetryPolicy retryPolicy) {
        this(repository, crypto, webhooks, retryPolicy, Runnable::run);
    }

    /** 构造带租户隔离执行池的 Webhook 投递服务。 */
    @Autowired
    public CustodyWebhookDispatchService(
            CustodyRepository repository,
            CustodyCryptoService crypto,
            CustodyWebhookService webhooks,
            CustodyWebhookRetryPolicy retryPolicy,
            TaskExecutor deliveryExecutor) {
        this.repository = repository;
        this.crypto = crypto;
        this.webhooks = webhooks;
        this.retryPolicy = retryPolicy;
        this.deliveryExecutor = deliveryExecutor;
    }

    /** 领取并投递一批待处理 Webhook。 */
    public void dispatch() {
        List<CustodyRepository.WebhookDeliveryTask> tasks =
                repository.claimWebhookDeliveries(workerId, 25);
        for (CustodyRepository.WebhookDeliveryTask task : tasks) {
            deliveryExecutor.execute(() -> deliver(task));
        }
    }

    /** 投递单个 Webhook，并根据返回结果更新状态。 */
    private void deliver(CustodyRepository.WebhookDeliveryTask task) {
        long startedAt = System.nanoTime();
        try {
            String secret = crypto.decrypt(task.secretCiphertext());
            CustodyWebhookService.WebhookHttpResult response = webhooks.send(
                    task.url(), secret, task.eventId(), task.eventType(), task.payload());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                repository.markWebhookDelivered(
                        task, response.statusCode(), response.body(), elapsedMs(startedAt));
                return;
            }
            fail(task, response.statusCode(),
                    "webhook returned HTTP " + response.statusCode(), response.body(),
                    response.retryAfter(), elapsedMs(startedAt));
        } catch (RuntimeException error) {
            log.warn("Custody webhook delivery {} failed: {}", task.id(), error.getMessage());
            fail(task, null, error.getMessage(), null, null, elapsedMs(startedAt));
        }
    }

    /** 计算失败后的重试状态并持久化。 */
    private void fail(CustodyRepository.WebhookDeliveryTask task, Integer httpStatus,
                      String error, String response, String retryAfter, long durationMs) {
        CustodyWebhookRetryPolicy.RetryDecision decision = retryPolicy.decide(
                task.id(), task.attemptCount(), retryAfter, java.time.Instant.now());
        repository.markWebhookFailed(
                task, httpStatus, error, response, decision.nextAttemptAt(),
                decision.terminal(), durationMs);
    }

    /** 计算单次投递耗时。 */
    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}

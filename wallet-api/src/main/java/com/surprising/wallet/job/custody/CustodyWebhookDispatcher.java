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

@Slf4j
@Component
public class CustodyWebhookDispatcher {
    private final CustodyRepository repository;
    private final CustodyCryptoService crypto;
    private final CustodyWebhookService webhooks;
    private final CustodyWebhookRetryPolicy retryPolicy;
    private final String workerId = "webhook-" + UUID.randomUUID();
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

    private void fail(WebhookDeliveryTask task, Integer httpStatus, String error,
                      String response, String retryAfter, long durationMs) {
        CustodyWebhookRetryPolicy.RetryDecision decision = retryPolicy.decide(
                task.id(), task.attemptCount(), retryAfter, java.time.Instant.now());
        repository.markWebhookFailed(
                task, httpStatus, error, response, decision.nextAttemptAt(),
                decision.terminal(), durationMs);
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}

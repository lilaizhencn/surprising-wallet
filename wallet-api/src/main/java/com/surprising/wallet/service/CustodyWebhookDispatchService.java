package com.surprising.wallet.service;

import com.surprising.wallet.gateway.CustodyWebhookHttpClient;
import com.surprising.wallet.model.CustodyWebhookRetryPolicy;
import com.surprising.wallet.model.custody.WebhookDeliveryTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 领取提交后才发送 HTTP；结果持久化失败保留租约，等待恢复。 */
@Slf4j
@Service
public class CustodyWebhookDispatchService {
    private final CustodyWebhookService webhooks;
    private final CustodyCryptoService crypto;
    private final CustodyWebhookHttpClient http;
    private final CustodyWebhookRetryPolicy retryPolicy;
    private final TaskExecutor deliveryExecutor;
    private final String workerId = "webhook-" + UUID.randomUUID();

    public CustodyWebhookDispatchService(CustodyWebhookService webhooks, CustodyCryptoService crypto,
                                         CustodyWebhookHttpClient http, CustodyWebhookRetryPolicy retryPolicy,
                                         @Qualifier("custodyWebhookExecutor") TaskExecutor deliveryExecutor) {
        this.webhooks = webhooks;
        this.crypto = crypto;
        this.http = http;
        this.retryPolicy = retryPolicy;
        this.deliveryExecutor = deliveryExecutor;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch() {
        for (WebhookDeliveryTask task : webhooks.claimWebhookDeliveries(workerId, 25)) {
            deliveryExecutor.execute(() -> {
                try { deliver(task); }
                catch (RuntimeException error) {
                    log.warn("Custody webhook delivery {} persistence failed: {}", task.id(), error.getMessage());
                }
            });
        }
    }

    private void deliver(WebhookDeliveryTask task) {
        long startedAt = System.nanoTime();
        CustodyWebhookHttpClient.WebhookHttpResult response;
        try {
            response = http.send(task.url(), crypto.decrypt(task.secretCiphertext()), task.eventId(), task.eventType(), task.payload());
        } catch (RuntimeException error) {
            log.warn("Custody webhook delivery {} request failed: {}", task.id(), error.getMessage());
            fail(task, null, error.getMessage(), null, null, elapsedMs(startedAt));
            return;
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            webhooks.markWebhookDelivered(task, response.statusCode(), response.body(), elapsedMs(startedAt));
        } else {
            fail(task, response.statusCode(), "webhook returned HTTP " + response.statusCode(),
                    response.body(), response.retryAfter(), elapsedMs(startedAt));
        }
    }

    private void fail(WebhookDeliveryTask task, Integer httpStatus, String error,
                      String response, String retryAfter, long durationMs) {
        var decision = retryPolicy.decide(task.id(), task.attemptCount(), retryAfter, Instant.now());
        webhooks.markWebhookFailed(task, httpStatus, error, response, decision.nextAttemptAt(), decision.terminal(), durationMs);
    }

    private static long elapsedMs(long startedAt) { return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000); }
}

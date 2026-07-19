package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyRepository.WebhookDeliveryTask;
import com.surprising.wallet.jobs.custody.CustodyWebhookService.WebhookHttpResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CustodyWebhookDispatcher {
    private static final int MAX_ATTEMPTS = 10;

    private final CustodyRepository repository;
    private final CustodyCryptoService crypto;
    private final CustodyWebhookService webhooks;
    private final String workerId = "webhook-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();

    public CustodyWebhookDispatcher(CustodyRepository repository,
                                    CustodyCryptoService crypto,
                                    CustodyWebhookService webhooks) {
        this.repository = repository;
        this.crypto = crypto;
        this.webhooks = webhooks;
    }

    @Scheduled(fixedDelayString = "${sw.wallet.custody.webhook-dispatch-delay:1000}")
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
        try {
            String secret = crypto.decrypt(task.secretCiphertext());
            WebhookHttpResult response = webhooks.send(
                    task.url(), secret, task.eventId(), task.eventType(), task.payload());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                repository.markWebhookDelivered(task, response.statusCode(), response.body());
                return;
            }
            fail(task, response.statusCode(),
                    "webhook returned HTTP " + response.statusCode(), response.body());
        } catch (RuntimeException e) {
            log.warn("Custody webhook delivery {} failed: {}", task.id(), e.getMessage());
            fail(task, null, e.getMessage(), null);
        }
    }

    private void fail(WebhookDeliveryTask task, Integer httpStatus, String error, String response) {
        boolean terminal = task.attemptCount() >= MAX_ATTEMPTS;
        long delaySeconds = Math.min(21_600L,
                60L * (1L << Math.min(Math.max(task.attemptCount() - 1, 0), 8)));
        repository.markWebhookFailed(
                task, httpStatus, error, response, Instant.now().plusSeconds(delaySeconds), terminal);
    }
}

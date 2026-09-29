package com.surprising.wallet.common.queue;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Function;

/** Claims durably, then commits the business action, next stage and acknowledgement together. */
public final class QueueWorker {
    private static final System.Logger LOG = System.getLogger(QueueWorker.class.getName());
    private final PgmqClient client;
    private final TransactionTemplate transaction;
    public QueueWorker(PgmqClient client, PlatformTransactionManager manager) {
        this.client = client;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Next(WalletQueue queue, String body) { }

    public void drain(WalletQueue queue, int limit, Function<PgmqClient.Message, Next> handler) {
        for (int i = 0; i < limit; i++) {
            var messages = client.read(queue, 300, 1);
            if (messages.isEmpty()) return;
            var message = messages.getFirst();
            try {
                transaction.executeWithoutResult(status -> {
                    if (!client.lock(queue, message)) return;
                    Next next = handler.apply(message);
                    if (next != null) client.send(next.queue(), next.body(), message.headers());
                    client.archive(queue, message);
                });
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING, "queue={0} id={1} attempt={2} error={3}",
                        queue.queueName(), message.id(), message.reads(), error.getClass().getSimpleName());
                transaction.executeWithoutResult(status -> {
                    if (!client.lock(queue, message)) return;
                    // Persist the exception type, never payloads, keys or RPC credentials.
                    String reason = error.getClass().getSimpleName();
                    if (message.reads() >= 20) client.deadLetter(queue, message, reason);
                    else client.retry(queue, message, reason, Math.min(900, 1 << Math.min(message.reads(), 9)));
                });
            }
        }
    }
}

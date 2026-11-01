package com.surprising.wallet.common.queue;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.function.BiPredicate;
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
        process(queue, limit, message -> null, (message, ignored) -> {
            Next next = handler.apply(message);
            if (next != null) client.send(next.queue(), next.body(), message.headers());
            return true;
        });
    }

    /** Work must fence its preparation and commit the intent before external calls; completion/next stage and acknowledgement share a transaction. */
    public <T> void drainRemote(WalletQueue queue, int limit, Function<PgmqClient.Message, T> work,
                                BiPredicate<PgmqClient.Message, T> completion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("remote queue processing requires a committed caller transaction");
        }
        process(queue, limit, work, completion);
    }

    private <T> void process(WalletQueue queue, int limit, Function<PgmqClient.Message, T> work,
                              BiPredicate<PgmqClient.Message, T> completion) {
        for (int i = 0; i < limit; i++) {
            var messages = client.read(queue, 300, 1);
            if (messages.isEmpty()) return;
            var message = messages.getFirst();
            try {
                T outcome = work.apply(message);
                transaction.executeWithoutResult(status -> {
                    if (!client.lock(queue, message)) return;
                    if (completion.test(message, outcome)) client.archive(queue, message);
                    else retry(queue, message, "Pending");
                });
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING, "queue={0} id={1} attempt={2} error={3}",
                        queue.queueName(), message.id(), message.reads(), error.getClass().getSimpleName());
                transaction.executeWithoutResult(status -> {
                    if (!client.lock(queue, message)) return;
                    retry(queue, message, error.getClass().getSimpleName());
                });
            }
        }
    }

    private void retry(WalletQueue queue, PgmqClient.Message message, String reason) {
        // Persist only a fixed state or exception type, never payloads, keys or RPC credentials.
        if (message.reads() >= 20) client.deadLetter(queue, message, reason);
        else client.retry(queue, message, reason, Math.min(900, 1 << Math.min(message.reads(), 9)));
    }
}

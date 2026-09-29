package com.surprising.wallet.service;

import com.surprising.wallet.repository.WalletOutboxRepository;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.WalletQueue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletOutboxDispatchService {
    public static final String SIGNING_FIRST_TOPIC = "SIGNING_FIRST";
    public static final String SIGNING_SECOND_TOPIC = "SIGNING_SECOND";
    private final WalletOutboxRepository outbox;
    private final PgmqClient queue;
    private final String workerId;
    public WalletOutboxDispatchService(WalletOutboxRepository outbox, PgmqClient queue,
                                      WalletTaskLeaseService leaseService) {
        this.outbox = outbox;
        this.queue = queue;
        this.workerId = leaseService.ownerId() + "-outbox";
    }
    @Transactional(rollbackFor = Throwable.class)
    public void dispatch(String topic, WalletQueue target) {
        for (var record : outbox.claim(topic, workerId, 100)) {
            queue.send(target, record.payload(), "{\"tenant_id\":\"" + record.tenantId()
                    + "\",\"outbox_id\":\"" + record.id() + "\"}");
            if (outbox.markDispatched(record.id(), workerId) != 1)
                throw new IllegalStateException("outbox ownership lost");
        }
    }
}

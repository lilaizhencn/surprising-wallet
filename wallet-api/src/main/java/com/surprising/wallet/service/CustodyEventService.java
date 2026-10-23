package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyEventRepository;
import com.surprising.wallet.repository.CustodyWebhookDeliveryRepository;
import com.surprising.wallet.repository.CustodyWebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persist a business event and its eligible webhook deliveries atomically. */
@Service
@RequiredArgsConstructor
public class CustodyEventService {
    private final CustodyEventRepository events;
    private final CustodyWebhookEndpointRepository endpoints;
    private final CustodyWebhookDeliveryRepository deliveries;

    @Transactional(rollbackFor = Throwable.class)
    public UUID publish(UUID eventId, UUID tenantId, String eventType,
                        String aggregateType, String aggregateId, String payload, boolean apiSource) {
        UUID persisted = events.insertIfAbsent(eventId, tenantId, eventType, aggregateType, aggregateId, payload)
                .or(() -> events.findIdByBusinessKey(tenantId, eventType, aggregateType, aggregateId))
                .orElseThrow(() -> new IllegalStateException("failed to persist custody event"));
        if (apiSource) {
            for (UUID endpointId : endpoints.listActiveIds(tenantId)) {
                deliveries.insert(UUID.randomUUID(), tenantId, endpointId, persisted);
            }
        }
        events.markPublished(persisted);
        return persisted;
    }
}

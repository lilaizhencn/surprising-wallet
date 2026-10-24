package com.surprising.wallet.model.custody;

import java.util.UUID;

public record CustodyEventPayload(
        UUID id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload
) {
}

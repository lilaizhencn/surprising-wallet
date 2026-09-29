package com.surprising.wallet.common.queue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QueueTenantTest {
    @Test void signingRejectsMissingOrDifferentTenant() {
        var mapper = new ObjectMapper();
        var tenant = UUID.randomUUID();
        var message = new PgmqClient.Message(1, 1, "{}", QueueTenant.headers(tenant));
        assertDoesNotThrow(() -> QueueTenant.verifySignature(mapper, message, "{\"tenantId\":\"" + tenant + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> QueueTenant.verifySignature(mapper, message, "{}"));
        assertThrows(IllegalArgumentException.class, () -> QueueTenant.verifySignature(mapper, message,
                "{\"tenantId\":\"" + UUID.randomUUID() + "\"}"));
    }
}

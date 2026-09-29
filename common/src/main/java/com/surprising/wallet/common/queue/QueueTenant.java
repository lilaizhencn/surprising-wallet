package com.surprising.wallet.common.queue;

import com.surprising.wallet.common.json.JacksonJson;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/** Tenant identity is retained through every queue stage and checked against signing payloads. */
public final class QueueTenant {
    private QueueTenant() { }
    public static UUID require(ObjectMapper mapper, PgmqClient.Message message) {
        var headers = JacksonJson.readObject(mapper, message.headers());
        return UUID.fromString(headers.path("tenant_id").asText());
    }
    public static void verifySignature(ObjectMapper mapper, PgmqClient.Message message, String signature) {
        UUID expected = require(mapper, message);
        UUID actual = UUID.fromString(JacksonJson.readObject(mapper, signature).path("tenantId").asText());
        if (!expected.equals(actual)) throw new IllegalArgumentException("queue tenant mismatch");
    }
    public static String headers(UUID tenant) {
        return "{\"tenant_id\":\"" + java.util.Objects.requireNonNull(tenant) + "\"}";
    }
}

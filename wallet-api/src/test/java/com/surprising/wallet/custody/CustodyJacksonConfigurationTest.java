package com.surprising.wallet.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.custody.config.CustodyJacksonConfiguration;

class CustodyJacksonConfigurationTest {
    @Test
    void serializesJavaTimeAsIso8601ForApiAndWebhookPayloads() throws Exception {
        ObjectMapper mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        Instant instant = Instant.parse("2026-07-20T00:00:00Z");

        String json = mapper.writeValueAsString(Map.of("createdAt", instant));

        assertEquals("{\"createdAt\":\"2026-07-20T00:00:00Z\"}", json);
        assertEquals(instant, mapper.readTree(json).path("createdAt").traverse(mapper)
                .readValueAs(Instant.class));
    }
}

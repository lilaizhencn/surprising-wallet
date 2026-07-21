package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustodyConsoleWebhookControllerTest {
    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    @Test
    void createWebhookAcceptsOnlyNameAndUrl() throws Exception {
        CustodyWebhookService.CreateWebhookCommand request = objectMapper.readValue("""
                {
                  "name": "Production events",
                  "url": "https://example.com/webhooks/custody"
                }
                """, CustodyWebhookService.CreateWebhookCommand.class);

        assertEquals("Production events", request.name());
        assertEquals("https://example.com/webhooks/custody", request.url());
    }

    @Test
    void createWebhookRejectsEventSelection() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue("""
                {
                  "name": "Production events",
                  "url": "https://example.com/webhooks/custody",
                  "events": ["DEPOSIT.CONFIRMED"]
                }
                """, CustodyWebhookService.CreateWebhookCommand.class));
    }
}

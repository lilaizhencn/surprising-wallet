package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyAddressServiceTest {

    @Test
    void auditDetailsAllowAnUnboundConsoleAddress() throws Exception {
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyAddressService service = new CustodyAddressService(
                null, null, null, null, objectMapper);

        JsonNode details = objectMapper.readTree(
                service.addressAuditDetails("ETH", "CONSOLE", null));

        assertEquals("ETH", details.path("chain").asText());
        assertEquals("CONSOLE", details.path("source").asText());
        assertTrue(details.has("externalReference"));
        assertTrue(details.get("externalReference").isNull());
    }
}

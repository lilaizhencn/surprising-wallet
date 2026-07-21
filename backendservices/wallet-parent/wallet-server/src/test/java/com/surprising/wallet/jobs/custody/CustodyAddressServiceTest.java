package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyAddressServiceTest {

    @Test
    void auditDetailsRecordSubjectAndChildIndex() throws Exception {
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyAddressService service = new CustodyAddressService(
                null, null, null, null, objectMapper);

        JsonNode details = objectMapper.readTree(
                service.addressAuditDetails("ETH", "CONSOLE", "treasury", 3));

        assertEquals("ETH", details.path("chain").asText());
        assertEquals("CONSOLE", details.path("source").asText());
        assertEquals("treasury", details.path("subject").asText());
        assertEquals(3, details.path("childIndex").asLong());
    }

    @Test
    void tenantSubjectsCannotUseReservedSystemPrefix() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CustodyAddressService.requireSubject("__sw_gas_reserve__:eth", false));

        assertTrue(error.getMessage().contains("reserved"));
        assertEquals("__sw_gas_reserve__:eth",
                CustodyAddressService.requireSubject("__sw_gas_reserve__:eth", true));
    }
}

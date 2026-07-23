package com.surprising.wallet.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.surprising.wallet.custody.controller.console.CustodyConsoleSecurityController.CreateApiKeyRequest;
import com.surprising.wallet.custody.controller.console.CustodyConsoleSecurityController;
import com.surprising.wallet.custody.config.CustodyJacksonConfiguration;

class CustodyConsoleSecurityControllerTest {
    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    @Test
    void createApiKeyAcceptsOnlyName() throws Exception {
        CustodyConsoleSecurityController.CreateApiKeyRequest request = objectMapper.readValue("""
                {
                  "name": "Production backend"
                }
                """, CustodyConsoleSecurityController.CreateApiKeyRequest.class);

        assertEquals("Production backend", request.name());
    }

    @Test
    void createApiKeyRejectsScopeSelection() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue("""
                {
                  "name": "Production backend",
                  "scopes": ["addresses:read"]
                }
                """, CustodyConsoleSecurityController.CreateApiKeyRequest.class));
    }
}

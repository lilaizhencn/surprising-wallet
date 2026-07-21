package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustodyPublicApiControllerTest {

    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    @Test
    void createAddressRequestAcceptsOnlyChainId() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals("ETH", request.chainId());
    }

    @Test
    void createAddressRequestRejectsTheOldChainField() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chain\":\"ETH\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }

    @Test
    void createAddressRequestRejectsAllocationMetadata() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"externalReference\":\"customer-1\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }
}

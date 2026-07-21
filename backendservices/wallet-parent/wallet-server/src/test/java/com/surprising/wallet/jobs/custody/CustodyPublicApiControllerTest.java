package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustodyPublicApiControllerTest {

    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    @Test
    void createAddressRequestAcceptsChainIdAndSubject() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"user_10086\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals("ETH", request.chainId());
        assertEquals("user_10086", request.subject());
    }

    @Test
    void createAddressEndpointDoesNotRequireIdempotencyHeader() throws Exception {
        assertEquals(2, CustodyPublicApiController.class.getDeclaredMethod(
                "createAddress",
                CustodyPublicApiController.CreatePublicAddressRequest.class,
                HttpServletRequest.class).getParameterCount());
    }

    @Test
    void createAddressRequestRejectsTheOldChainField() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chain\":\"ETH\",\"subject\":\"user_10086\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }

    @Test
    void createAddressRequestRejectsAllocationMetadata() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"customer-1\",\"externalReference\":\"customer-1\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }
}

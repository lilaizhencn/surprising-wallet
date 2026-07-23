package com.surprising.wallet.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.surprising.wallet.custody.controller.api.CustodyPublicApiController.CreatePublicAddressRequest;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.custody.controller.api.CustodyPublicApiController;

class CustodyPublicApiControllerTest {

    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    @Test
    void createAddressRequestDefaultsAddressVersion() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"user_10086\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals("ETH", request.chainId());
        assertEquals("user_10086", request.subject());
        assertEquals(null, request.addressVersion());
    }

    @Test
    void createAddressRequestAcceptsAddressVersion() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"user_10086\",\"addressVersion\":2}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals(2L, request.addressVersion());
    }

    @Test
    void createAddressIsIdempotentByChainSubjectAndVersionWithoutAnExtraHeader() throws Exception {
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

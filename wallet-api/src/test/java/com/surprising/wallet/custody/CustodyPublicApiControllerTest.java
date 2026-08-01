package com.surprising.wallet.custody;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.controller.CustodyPublicApiController;

/**
 * 验证 {@code CustodyPublicApiControllerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyPublicApiControllerTest {

    /**
     * 保存 {@code objectMapper}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    /**
     * 验证 {@code createAddressRequestDefaultsAddressVersion} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createAddressRequestDefaultsAddressVersion() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"user_10086\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals("ETH", request.chainId());
        assertEquals("user_10086", request.subject());
        assertEquals(null, request.addressVersion());
    }

    /**
     * 验证 {@code createAddressRequestAcceptsAddressVersion} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createAddressRequestAcceptsAddressVersion() throws Exception {
        CustodyPublicApiController.CreatePublicAddressRequest request = objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"user_10086\",\"addressVersion\":2}",
                CustodyPublicApiController.CreatePublicAddressRequest.class);

        assertEquals(2L, request.addressVersion());
    }

    /**
     * 验证 {@code createAddressIsIdempotentByChainSubjectAndVersionWithoutAnExtraHeader} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createAddressIsIdempotentByChainSubjectAndVersionWithoutAnExtraHeader() throws Exception {
        assertEquals(2, CustodyPublicApiController.class.getDeclaredMethod(
                "createAddress",
                CustodyPublicApiController.CreatePublicAddressRequest.class,
                HttpServletRequest.class).getParameterCount());
    }

    /**
     * 验证 {@code createAddressRequestRejectsTheOldChainField} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createAddressRequestRejectsTheOldChainField() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chain\":\"ETH\",\"subject\":\"user_10086\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }

    /**
     * 验证 {@code createAddressRequestRejectsAllocationMetadata} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createAddressRequestRejectsAllocationMetadata() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue(
                "{\"chainId\":\"ETH\",\"subject\":\"customer-1\",\"externalReference\":\"customer-1\"}",
                CustodyPublicApiController.CreatePublicAddressRequest.class));
    }
}

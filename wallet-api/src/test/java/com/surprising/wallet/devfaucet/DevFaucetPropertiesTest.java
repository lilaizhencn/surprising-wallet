package com.surprising.wallet.devfaucet;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.devfaucet.model.DevFaucetProperties;
import com.surprising.wallet.devfaucet.model.DevFaucetAmountGenerator;
import com.surprising.wallet.service.JsonRpcDevFaucetClient;
import com.surprising.wallet.service.DevFaucetRpcClient;

/**
 * 验证 {@code DevFaucetPropertiesTest} 覆盖的业务流程、边界条件和异常行为。
 */
class DevFaucetPropertiesTest {
    /**
     * 验证 {@code enabledFaucetAcceptsOnlyDevelopmentEnvironmentAndLoopbackNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledFaucetAcceptsOnlyDevelopmentEnvironmentAndLoopbackNodes() {
        DevFaucetProperties properties = validProperties();
        properties.validate("test2");

        assertThrows(IllegalStateException.class, () -> properties.validate("prod"));

        properties.getEvm().setRpcUrl("https://rpc.example.com");
        assertThrows(IllegalStateException.class, () -> properties.validate("dev"));
    }

    /**
     * 验证 {@code randomAmountsStayInsideConfiguredAtomicRange} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void randomAmountsStayInsideConfiguredAtomicRange() {
        DevFaucetProperties properties = validProperties();
        DevFaucetAmountGenerator generator = new DevFaucetAmountGenerator(new Random(17));

        for (int i = 0; i < 100; i++) {
            BigDecimal amount = generator.next(properties.getEvm().getUsdt());
            assertTrue(amount.compareTo(new BigDecimal("10.00")) >= 0);
            assertTrue(amount.compareTo(new BigDecimal("100.00")) <= 0);
            assertEquals(2, amount.scale());
        }
    }

    /**
     * 验证 {@code erc20TransferEncodingUsesRecipientAndAtomicAmount} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void erc20TransferEncodingUsesRecipientAndAtomicAmount() {
        String encoded = JsonRpcDevFaucetClient.encodeTransfer(
                "0x00000000000000000000000000000000000000ab",
                BigDecimal.valueOf(12_345_678L).toBigIntegerExact());

        assertEquals(138, encoded.length());
        assertTrue(encoded.startsWith("0xa9059cbb"));
        assertTrue(encoded.endsWith("0000000000000000000000000000000000000000000000000000000000bc614e"));
    }

    /**
     * 验证 {@code localJsonRpcClientUsesHttp11InsteadOfH2cUpgrade} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void localJsonRpcClientUsesHttp11InsteadOfH2cUpgrade() throws Exception {
        JsonRpcDevFaucetClient client = new JsonRpcDevFaucetClient(
                validProperties(), new tools.jackson.databind.ObjectMapper());
        Field field = JsonRpcDevFaucetClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);

        assertEquals(HttpClient.Version.HTTP_1_1,
                ((HttpClient) field.get(client)).version());
    }

    /**
     * 验证 {@code validProperties} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    static DevFaucetProperties validProperties() {
        DevFaucetProperties properties = new DevFaucetProperties();
        properties.setEnabled(true);
        properties.getBitcoin().setRpcUsername("wallet");
        properties.getBitcoin().setRpcPassword("local-only");
        properties.getEvm().setFromAddress(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
        return properties;
    }
}

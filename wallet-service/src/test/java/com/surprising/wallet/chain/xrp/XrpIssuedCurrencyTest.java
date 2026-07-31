package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code XrpIssuedCurrencyTest} 覆盖的业务流程、边界条件和异常行为。
 */
class XrpIssuedCurrencyTest {
    /**
     * 保存 {@code CIRCLE_USDC_TESTNET_ISSUER}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String CIRCLE_USDC_TESTNET_ISSUER = "rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt";
    /**
     * 保存 {@code CIRCLE_USDC_HEX}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String CIRCLE_USDC_HEX = "5553444300000000000000000000000000000000";

    /**
     * 验证 {@code parsesCircleUsdcIssuedCurrencyConfig} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void parsesCircleUsdcIssuedCurrencyConfig() {
        TokenDefinition token = TokenDefinition.builder()
                .chain("XRP")
                .symbol("usdc")
                .contractAddress(CIRCLE_USDC_TESTNET_ISSUER + ":" + CIRCLE_USDC_HEX)
                .decimals(6)
                .build();

        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);

        assertEquals("USDC", issued.symbol());
        assertEquals(CIRCLE_USDC_TESTNET_ISSUER, issued.issuer());
        assertEquals(CIRCLE_USDC_HEX, issued.currencyCode());
        assertTrue(issued.matches(CIRCLE_USDC_TESTNET_ISSUER, CIRCLE_USDC_HEX.toLowerCase()));
        assertEquals("1.25", issued.amount(new BigDecimal("1.250000")).value());
    }

    /**
     * 验证 {@code rejectsMissingIssuerCurrencySeparator} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void rejectsMissingIssuerCurrencySeparator() {
        TokenDefinition token = TokenDefinition.builder()
                .chain("XRP")
                .symbol("USDC")
                .contractAddress(CIRCLE_USDC_TESTNET_ISSUER)
                .decimals(6)
                .build();

        assertThrows(IllegalArgumentException.class, () -> XrpIssuedCurrency.fromToken(token));
    }
}

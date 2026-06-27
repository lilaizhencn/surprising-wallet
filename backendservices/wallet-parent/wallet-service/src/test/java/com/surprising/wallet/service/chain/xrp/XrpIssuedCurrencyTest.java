package com.surprising.wallet.service.chain.xrp;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrpIssuedCurrencyTest {
    private static final String CIRCLE_USDC_TESTNET_ISSUER = "rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt";
    private static final String CIRCLE_USDC_HEX = "5553444300000000000000000000000000000000";

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

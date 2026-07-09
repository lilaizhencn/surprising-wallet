package com.surprising.wallet.service.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronLedgerIdempotencyTest {
    @Test
    void sameLedgerAndChainBalance_shouldMatch() {
        TronLedgerReconciliationService service = new TronLedgerReconciliationService();
        var result = service.compare("TRON_NILE", "USDT", "TAddress",
                new BigDecimal("10.000000"), new BigDecimal("10.000000"));
        assertTrue(result.matched());
    }

    @Test
    void differentLedgerAndChainBalance_shouldReportDelta() {
        TronLedgerReconciliationService service = new TronLedgerReconciliationService();
        var result = service.compare("TRON_NILE", "USDT", "TAddress",
                new BigDecimal("9"), new BigDecimal("10"));
        assertFalse(result.matched());
        assertTrue(result.delta().compareTo(BigDecimal.ZERO) > 0);
    }
}

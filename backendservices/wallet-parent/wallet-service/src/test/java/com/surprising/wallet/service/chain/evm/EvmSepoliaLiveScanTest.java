package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvmSepoliaLiveScanTest {
    private static final String TX_ID = "0x086c6c6083d60bfffe2e5873d3388fd41a5c68499221369f248621305a4e97d3";
    private static final String ADDRESS = "0xa69c190e7c823fe23dcf6ed7c32877214458d4d3";
    private static final long BLOCK = 11099971L;

    @Test
    void shouldScanAndCreditGoogleCloudSepoliaFaucetTx() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.live.enabled"),
                "set -Devm.live.enabled=true to run live Sepolia scan");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbcTemplate);
        EvmDepositScanner scanner = new EvmDepositScanner(repository,
                System.getProperty("evm.rpc", "https://ethereum-sepolia-rpc.publicnode.com"),
                Integer.getInteger("evm.confirmations", 12));

        BigDecimal onChainBalance = scanner.getNativeBalance(ADDRESS);
        assertTrue(onChainBalance.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(scanner.getGasPriceGwei().compareTo(BigDecimal.ZERO) > 0);
        BigInteger nonce = scanner.getPendingNonce(ADDRESS);
        assertTrue(nonce.signum() >= 0);

        List<DepositEvent> events = scanner.scanAndCreditNativeEth(BLOCK);
        assertTrue(events.stream().anyMatch(event -> TX_ID.equalsIgnoreCase(event.txId())));

        Map<String, Object> deposit = jdbcTemplate.queryForMap("""
                        select amount, confirmations, status, credited
                        from deposit_record
                        where chain = 'ETH' and tx_hash = ? and log_index = 0
                        """, TX_ID);
        assertEquals(0, new BigDecimal("0.05").compareTo((BigDecimal) deposit.get("amount")));
        assertEquals("CREDITED", deposit.get("status"));
        assertEquals(Boolean.TRUE, deposit.get("credited"));
        assertTrue(((Number) deposit.get("confirmations")).intValue() >= 12);

        BigDecimal afterFirstScan = ledgerBalance(jdbcTemplate);
        scanner.scanAndCreditNativeEth(BLOCK);
        BigDecimal afterSecondScan = ledgerBalance(jdbcTemplate);
        assertEquals(0, afterFirstScan.compareTo(afterSecondScan), "duplicate scan must not double credit ledger");
        assertTrue(afterSecondScan.compareTo(new BigDecimal("0.05")) >= 0);
    }

    private static BigDecimal ledgerBalance(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("""
                        select available_balance
                        from ledger_balance
                        where chain = 'ETH' and asset_symbol = 'ETH' and account_id = ?
                        """, BigDecimal.class, ADDRESS);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("evm.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("evm.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("evm.db.password", "wallet123"));
        return dataSource;
    }
}

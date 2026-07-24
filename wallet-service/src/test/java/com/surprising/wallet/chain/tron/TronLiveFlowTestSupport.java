package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TronLiveFlowTestSupport {
    static final String CHAIN = "TRON";

    private TronLiveFlowTestSupport() {
    }

    static Map<String, String> reportOrSkip() throws Exception {
        Path report = Path.of("target", "tron-live-flow-report.properties");
        Assumptions.assumeTrue(Files.exists(report),
                "run TronLiveFullFlowIntegrationTest with -Dtron.live.flow.enabled=true first");
        return Files.readAllLines(report).stream()
                .filter(line -> line.contains("="))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }

    static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("tron.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("tron.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("tron.db.password", "wallet123"));
        return new JdbcTemplate(dataSource);
    }

    static void assertCreditedDeposit(JdbcTemplate jdbcTemplate, String txId, String address, String asset,
                                      BigDecimal amount) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                        select amount, status, credited
                        from deposit_record
                        where chain = ? and tx_hash = ? and lower(to_address) = lower(?) and asset_symbol = ?
                        """, CHAIN, txId, address, asset);
        assertEquals(0, amount.compareTo((BigDecimal) row.get("amount")));
        assertEquals("CREDITED", row.get("status"));
        assertEquals(Boolean.TRUE, row.get("credited"));
    }

    static void assertConfirmedWithdrawal(JdbcTemplate jdbcTemplate, String txId, String asset) {
        String status = jdbcTemplate.queryForObject("""
                        select status from withdrawal_order
                        where chain = ? and tx_hash = ? and asset_symbol = ?
                        """, String.class, CHAIN, txId, asset);
        assertEquals("CONFIRMED", status);
    }

    static void assertConfirmedCollection(JdbcTemplate jdbcTemplate, String txId) {
        String status = jdbcTemplate.queryForObject("""
                        select status from collection_record
                        where chain = ? and tx_hash = ?
                        """, String.class, CHAIN, txId);
        assertEquals("CONFIRMED", status);
    }

    static void assertConfirmedGasTopup(JdbcTemplate jdbcTemplate, String txId) {
        String status = jdbcTemplate.queryForObject("""
                        select status from gas_topup_task
                        where chain = ? and tx_hash = ?
                        """, String.class, CHAIN, txId);
        assertEquals("CONFIRMED", status);
    }

    static void assertNoLockedOrNegativeLedger(JdbcTemplate jdbcTemplate, String... addresses) {
        for (String address : addresses) {
            Integer count = jdbcTemplate.queryForObject("""
                            select count(*) from ledger_balance
                            where chain = ? and lower(account_id) = lower(?)
                              and (available_balance < 0 or locked_balance <> 0 or total_balance < 0)
                            """, Integer.class, CHAIN, address);
            assertEquals(0, count);
        }
    }

    static BigDecimal ledger(JdbcTemplate jdbcTemplate, String asset, String address) {
        List<BigDecimal> rows = jdbcTemplate.queryForList("""
                        select available_balance from ledger_balance
                        where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                        """, BigDecimal.class, CHAIN, asset, address);
        assertTrue(rows.size() <= 1);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
    }
}

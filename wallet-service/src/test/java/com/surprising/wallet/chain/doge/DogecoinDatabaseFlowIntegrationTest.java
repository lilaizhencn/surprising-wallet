package com.surprising.wallet.chain.doge;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-backed DOGE idempotency, ledger, UTXO lock/release, and recovery
 * model validation. It is opt-in because the default Maven build is hermetic.
 */
class DogecoinDatabaseFlowIntegrationTest {
    @Test
    void syntheticFlowMustRemainIdempotentAndNonNegative() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("doge.db.enabled"),
                "set -Ddoge.db.enabled=true for PostgreSQL-backed DOGE flow validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("DOGE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("DOGE_DB_USER", "wallet"),
                env("DOGE_DB_PASSWORD", ""));
        ChainJdbcRepository repository = new ChainJdbcRepository(new JdbcTemplate(dataSource));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            repository = new ChainJdbcRepository(jdbc);
            for (ChainType chainType : List.of(ChainType.DOGE, ChainType.BCH)) {
                String chain = chainType.name();
                String txid = UUID.randomUUID().toString().replace("-", "") + "00";
                String account = chain.toLowerCase() + "-test-" + UUID.randomUUID();
                DepositEvent event = new DepositEvent(
                        chainType, chain, txid, null, "synthetic",
                        new BigDecimal("25.00000000"), 1L, txid, 6, null, "{}");

                assertTrue(repository.recordAndCreditDeposit(event, 0, 6, account));
                assertFalse(repository.recordAndCreditDeposit(event, 0, 6, account));
                repository.upsertUtxo(
                        chain, chain, txid, 0, "synthetic",
                        new BigDecimal("25.00000000"), 1L, txid, 6, true);
                assertEquals(1, repository.lockUtxo(chain, txid, 0, "lock-1"));
                assertEquals(0, repository.lockUtxo(chain, txid, 0, "lock-2"));
                assertEquals(1, repository.releaseUtxos(chain, "lock-1"));

                BigDecimal total = jdbc.queryForObject("""
                    select total_balance from ledger_balance
                    where chain=? and asset_symbol=? and account_id=?
                    """, BigDecimal.class, chain, chain, account);
                assertEquals(new BigDecimal("25.000000000000000000"), total);
                assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from deposit_record
                    where chain=? and tx_hash=? and log_index=0
                    """, Long.class, chain, txid));
                assertEquals(0L, jdbc.queryForObject("""
                    select count(*) from ledger_balance
                    where chain=?
                      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                    """, Long.class, chain));

                String scannerName = chain.toLowerCase() + "-scanner-" + UUID.randomUUID();
                repository.updateScanHeight(chain, scannerName, 10L, 9L);
                repository.updateScanHeight(chain, scannerName, 10L, 4L);
                assertEquals(4L, jdbc.queryForObject("""
                    select safe_height from chain_scan_height
                    where chain=? and scanner_name=?
                    """, Long.class, chain, scannerName));
                repository.updateScanHeight(chain, scannerName, 9L, 8L);
                assertEquals(4L, jdbc.queryForObject("""
                    select safe_height from chain_scan_height
                    where chain=? and scanner_name=?
                    """, Long.class, chain, scannerName),
                        "a stale lower best height must not move the safe checkpoint");
            }
            connection.rollback();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

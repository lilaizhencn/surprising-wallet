package com.surprising.wallet.service.chain.doge;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

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
            String txid = UUID.randomUUID().toString().replace("-", "") + "00";
            String account = "doge-test-" + UUID.randomUUID();
            DepositEvent event = new DepositEvent(
                    ChainType.DOGE, "DOGE", txid, null, "nSynthetic",
                    new BigDecimal("25.00000000"), 1L, 6, null, "{}");

            assertTrue(repository.recordAndCreditDeposit(event, 0, 6, account));
            assertFalse(repository.recordAndCreditDeposit(event, 0, 6, account));
            repository.upsertUtxo(
                    "DOGE", "DOGE", txid, 0, "nSynthetic",
                    new BigDecimal("25.00000000"), 1L, 6, true);
            assertEquals(1, repository.lockUtxo("DOGE", txid, 0, "lock-1"));
            assertEquals(0, repository.lockUtxo("DOGE", txid, 0, "lock-2"));
            assertEquals(1, repository.releaseUtxos("DOGE", "lock-1"));

            BigDecimal total = jdbc.queryForObject("""
                    select total_balance from ledger_balance
                    where chain='DOGE' and asset_symbol='DOGE' and account_id=?
                    """, BigDecimal.class, account);
            assertEquals(new BigDecimal("25.000000000000000000"), total);
            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from deposit_record
                    where chain='DOGE' and tx_hash=? and log_index=0
                    """, Long.class, txid));
            assertEquals(0L, jdbc.queryForObject("""
                    select count(*) from ledger_balance
                    where chain='DOGE'
                      and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                    """, Long.class));
            connection.rollback();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

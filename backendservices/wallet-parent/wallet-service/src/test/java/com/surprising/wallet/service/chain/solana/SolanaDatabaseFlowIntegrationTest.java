package com.surprising.wallet.service.chain.solana;

import com.surprising.wallet.common.chain.ChainAddressRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolanaDatabaseFlowIntegrationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void addressRestartDepositReplayAndLedgerLocksAreSafe() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("solana.db.enabled"),
                "set -Dsolana.db.enabled=true for PostgreSQL-backed Solana validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SOLANA_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SOLANA_DB_USER", "wallet"),
                env("SOLANA_DB_PASSWORD", ""));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            SolanaKeyService keys = new SolanaKeyService(MASTER_SEED);
            SolanaAddressService addresses = new SolanaAddressService(keys, repository);
            long baseIndex = 700_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);

            ChainAddressRecord userA = addresses.createNativeAddress(1001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userAAfterRestart = new SolanaAddressService(
                    new SolanaKeyService(MASTER_SEED), repository)
                    .createNativeAddress(1001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userB = addresses.createNativeAddress(1002, 0, baseIndex + 1, "DEPOSIT");
            assertEquals(userA.getAddress(), userAAfterRestart.getAddress());
            assertNotEquals(userA.getAddress(), userB.getAddress());

            String signature = UUID.randomUUID().toString().replace("-", "");
            DepositEvent deposit = new DepositEvent(ChainType.SOLANA, "SOL", signature,
                    "external", userA.getAddress(), new BigDecimal("10000000"),
                    123L, 2, null, "{}");
            assertTrue(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertFalse(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertTrue(repository.freezeLedgerBalance("SOLANA", "SOL", userA.getAccountId(),
                    new BigDecimal("2000000")));
            assertFalse(repository.freezeLedgerBalance("SOLANA", "SOL", userA.getAccountId(),
                    new BigDecimal("9000000")));
            assertTrue(repository.releaseLockedBalance("SOLANA", "SOL", userA.getAccountId(),
                    new BigDecimal("2000000")));

            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from deposit_record
                    where chain='SOLANA' and tx_hash=? and log_index=0
                    """, Long.class, signature));
            assertEquals(new BigDecimal("10000000.000000000000000000"),
                    jdbc.queryForObject("""
                            select total_balance from ledger_balance
                            where chain='SOLANA' and asset_symbol='SOL' and account_id=?
                            """, BigDecimal.class, userA.getAccountId()));
            assertEquals(0L, jdbc.queryForObject("""
                    select count(*) from ledger_balance
                    where chain='SOLANA'
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

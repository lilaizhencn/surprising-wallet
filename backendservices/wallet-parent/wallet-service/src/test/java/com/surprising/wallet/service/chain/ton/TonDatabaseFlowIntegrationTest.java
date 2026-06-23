package com.surprising.wallet.service.chain.ton;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TonDatabaseFlowIntegrationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void addressRestartDepositReplayLedgerLocksAndSeqnoReservationAreSafe() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ton.db.enabled"),
                "set -Dton.db.enabled=true for PostgreSQL-backed TON validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("TON_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("TON_DB_USER", "wallet"),
                env("TON_DB_PASSWORD", ""));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            TonKeyService keys = new TonKeyService(MASTER_SEED);
            TonAddressService addresses = new TonAddressService(keys, repository);
            long baseIndex = 900_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);

            ChainAddressRecord userA = addresses.createNativeAddress(3001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userAAfterRestart = new TonAddressService(
                    new TonKeyService(MASTER_SEED), repository)
                    .createNativeAddress(3001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userB = addresses.createNativeAddress(3002, 0, baseIndex + 1, "DEPOSIT");
            assertEquals(userA.getAddress(), userAAfterRestart.getAddress());
            assertNotEquals(userA.getAddress(), userB.getAddress());

            String txHash = UUID.randomUUID().toString().replace("-", "");
            DepositEvent deposit = new DepositEvent(ChainType.TON, "TON", txHash,
                    "external", userA.getAddress(), new BigDecimal("1000000000"),
                    123L, 1, null, "{}");
            assertTrue(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertFalse(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertTrue(repository.freezeLedgerBalance("TON", "TON", userA.getAccountId(),
                    new BigDecimal("200000000")));
            assertFalse(repository.freezeLedgerBalance("TON", "TON", userA.getAccountId(),
                    new BigDecimal("900000000")));
            assertTrue(repository.releaseLockedBalance("TON", "TON", userA.getAccountId(),
                    new BigDecimal("200000000")));

            long seq0 = repository.reserveAccountSequence("TON", userA.getAddress(), 4);
            long seq1 = repository.reserveAccountSequence("TON", userA.getAddress(), 4);
            assertEquals(4L, seq0);
            assertEquals(5L, seq1);

            repository.recordTonTransaction(TonTransactionRecord.builder()
                    .chain("TON")
                    .txHash(txHash)
                    .fromAddress("external")
                    .toAddress(userA.getAddress())
                    .assetSymbol("TON")
                    .amount(new BigDecimal("1000000000"))
                    .feeNano(1L)
                    .logicalTime(BigInteger.valueOf(123))
                    .confirmations(1)
                    .status("CONFIRMED")
                    .rawPayload("{}")
                    .build());
            repository.recordTonTransaction(TonTransactionRecord.builder()
                    .chain("TON")
                    .txHash(txHash)
                    .fromAddress("external")
                    .toAddress(userA.getAddress())
                    .assetSymbol("TON")
                    .amount(new BigDecimal("1000000000"))
                    .feeNano(2L)
                    .logicalTime(BigInteger.valueOf(123))
                    .confirmations(2)
                    .status("CONFIRMED")
                    .rawPayload("{}")
                    .build());

            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from deposit_record
                    where chain='TON' and tx_hash=? and log_index=0
                    """, Long.class, txHash));
            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from ton_transaction
                    where chain='TON' and tx_hash=?
                    """, Long.class, txHash));
            assertEquals(new BigDecimal("1000000000.000000000000000000"),
                    jdbc.queryForObject("""
                            select total_balance from ledger_balance
                            where chain='TON' and asset_symbol='TON' and account_id=?
                            """, BigDecimal.class, userA.getAccountId()));
            assertEquals(0L, jdbc.queryForObject("""
                    select count(*) from ledger_balance
                    where chain='TON'
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

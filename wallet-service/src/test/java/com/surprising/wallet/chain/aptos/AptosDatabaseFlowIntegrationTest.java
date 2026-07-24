package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosDatabaseFlowIntegrationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void addressRestartDepositReplayLedgerLocksAndSequenceReservationAreSafe() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.db.enabled"),
                "set -Daptos.db.enabled=true for PostgreSQL-backed Aptos validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("APTOS_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("APTOS_DB_USER", "wallet"),
                env("APTOS_DB_PASSWORD", ""));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            AptosKeyService keys = new AptosKeyService(MASTER_SEED);
            AptosAddressService addresses = new AptosAddressService(keys, repository);
            long baseIndex = 1_200_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);

            ChainAddressRecord userA = addresses.createNativeAddress(5001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userAAfterRestart = new AptosAddressService(
                    new AptosKeyService(MASTER_SEED), repository)
                    .createNativeAddress(5001, 0, baseIndex, "DEPOSIT");
            ChainAddressRecord userB = addresses.createNativeAddress(5002, 0, baseIndex + 1, "DEPOSIT");
            assertEquals(userA.getAddress(), userAAfterRestart.getAddress());
            assertNotEquals(userA.getAddress(), userB.getAddress());

            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            DepositEvent deposit = new DepositEvent(ChainType.APTOS, "APT", txHash,
                    "0x1", userA.getAddress(), new BigDecimal("100000000"),
                    123L, txHash, 1, null, "{}");
            assertTrue(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertFalse(repository.recordAndCreditDeposit(deposit, 0, 1, userA.getAccountId()));
            assertTrue(repository.freezeLedgerBalance("APTOS", "APT", userA.getAccountId(),
                    new BigDecimal("20000000")));
            assertFalse(repository.freezeLedgerBalance("APTOS", "APT", userA.getAccountId(),
                    new BigDecimal("90000000")));
            assertTrue(repository.releaseLockedBalance("APTOS", "APT", userA.getAccountId(),
                    new BigDecimal("20000000")));

            long seq0 = repository.reserveAccountSequence("APTOS", userA.getAddress(), 4);
            long seq1 = repository.reserveAccountSequence("APTOS", userA.getAddress(), 4);
            assertEquals(4L, seq0);
            assertEquals(5L, seq1);

            repository.recordAptosTransaction(AptosTransactionRecord.builder()
                    .chain("APTOS")
                    .txHash(txHash)
                    .sender("0x1")
                    .receiver(userA.getAddress())
                    .assetSymbol("APT")
                    .coinType(AptosRpcClient.aptCoinType())
                    .amount(new BigDecimal("100000000"))
                    .gasUsed(1L)
                    .gasUnitPrice(1L)
                    .version(123L)
                    .sequenceNumber(0L)
                    .confirmations(1)
                    .status("CONFIRMED")
                    .rawPayload("{}")
                    .build());
            repository.recordAptosTransaction(AptosTransactionRecord.builder()
                    .chain("APTOS")
                    .txHash(txHash)
                    .sender("0x1")
                    .receiver(userA.getAddress())
                    .assetSymbol("APT")
                    .coinType(AptosRpcClient.aptCoinType())
                    .amount(new BigDecimal("100000000"))
                    .gasUsed(2L)
                    .gasUnitPrice(2L)
                    .version(123L)
                    .sequenceNumber(0L)
                    .confirmations(2)
                    .status("CONFIRMED")
                    .rawPayload("{}")
                    .build());

            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from deposit_record
                    where chain='APTOS' and tx_hash=? and log_index=0
                    """, Long.class, txHash));
            assertEquals(1L, jdbc.queryForObject("""
                    select count(*) from aptos_transaction
                    where chain='APTOS' and tx_hash=?
                    """, Long.class, txHash));
            assertEquals(new BigDecimal("100000000.000000000000000000"),
                    jdbc.queryForObject("""
                            select total_balance from ledger_balance
                            where chain='APTOS' and asset_symbol='APT' and account_id=?
                            """, BigDecimal.class, userA.getAccountId()));
            assertEquals(0L, jdbc.queryForObject("""
                    select count(*) from ledger_balance
                    where chain='APTOS'
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

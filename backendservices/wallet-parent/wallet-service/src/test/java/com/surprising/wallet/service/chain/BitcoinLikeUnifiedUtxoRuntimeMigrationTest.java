package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-backed verification for the CurrencyEnum-to-DB asset migration:
 * BTC/LTC/DOGE/BCH runtime UTXO operations must use utxo_record as the source
 * of truth and must not require legacy *_utxo_transaction rows.
 */
class BitcoinLikeUnifiedUtxoRuntimeMigrationTest {
    @Test
    void unifiedUtxoRecordMustSupportSelectionLockReleaseSpendAndLedgerIdempotency() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("utxo.migration.db.enabled"),
                "set -Dutxo.migration.db.enabled=true for PostgreSQL-backed UTXO migration validation");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("UTXO_MIGRATION_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("UTXO_MIGRATION_DB_USER", "wallet"),
                env("UTXO_MIGRATION_DB_PASSWORD", ""));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);

            try {
                for (ChainType chainType : List.of(ChainType.BTC, ChainType.LTC, ChainType.DOGE, ChainType.BCH)) {
                    String chain = chainType.name();
                    String txHash = "migration" + UUID.randomUUID().toString().replace("-", "");
                    String address = "migration-address-" + chain.toLowerCase();
                    String accountId = "migration-account-" + chain.toLowerCase() + "-" + UUID.randomUUID();
                    BigDecimal amount = new BigDecimal("1.25000000");

                    repository.upsertUtxo(chain, chain, txHash, 0, address, amount, 100L, 6, false);

                    assertLegacyUtxoAbsent(jdbc, chain, txHash);
                    List<UtxoTransaction> spendable = repository.listSpendableUtxos(chain, chain, 1, 10, 0);
                    UtxoTransaction selected = spendable.stream()
                            .filter(utxo -> txHash.equals(utxo.getTxId()) && utxo.getSeq() == 0)
                            .findFirst()
                            .orElseThrow();
                    assertEquals(amount.setScale(18), selected.getBalance());
                    assertEquals(CurrencyEnum.parseName(chain).getIndex(), selected.getCurrency());

                    String lockRef = "migration-lock-" + UUID.randomUUID();
                    assertEquals(1, repository.lockUtxo(chain, txHash, 0, lockRef));
                    assertEquals(0, repository.lockUtxo(chain, txHash, 0, lockRef + "-duplicate"));
                    assertEquals(1, repository.releaseUtxos(chain, lockRef));
                    assertEquals(1, repository.lockUtxo(chain, txHash, 0, lockRef));
                    assertEquals(1, repository.markUtxosSpent(chain, lockRef, txHash + "-spent"));
                    assertEquals(0, repository.releaseUtxos(chain, lockRef));

                    String depositTx = "deposit" + UUID.randomUUID().toString().replace("-", "");
                    DepositEvent event = new DepositEvent(
                            chainType, chain, depositTx, null, address,
                            amount, 101L, 6, null, "{}");
                    assertTrue(repository.recordAndCreditDeposit(event, 0, 6, accountId));
                    assertFalse(repository.recordAndCreditDeposit(event, 0, 6, accountId));
                    assertTrue(repository.depositRecordExists(chain, depositTx, 0));
                    assertEquals(amount.setScale(18), jdbc.queryForObject("""
                            select total_balance from ledger_balance
                            where chain=? and asset_symbol=? and account_id=?
                            """, BigDecimal.class, chain, chain, accountId));
                    assertEquals(1L, jdbc.queryForObject("""
                            select count(*) from deposit_record
                            where chain=? and tx_hash=? and log_index=0 and credited=true
                            """, Long.class, chain, depositTx));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertLegacyUtxoAbsent(JdbcTemplate jdbc, String chain, String txHash) {
        String table = switch (chain) {
            case "BTC" -> "btc_utxo_transaction";
            case "LTC" -> "ltc_utxo_transaction";
            case "DOGE" -> "doge_utxo_transaction";
            case "BCH" -> "bch_utxo_transaction";
            default -> throw new IllegalArgumentException("unsupported chain " + chain);
        };
        Long count = jdbc.queryForObject(
                "select count(*) from " + table + " where tx_id = ?",
                Long.class,
                txHash);
        assertEquals(0L, count);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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
 * PostgreSQL-backed verification for the AssetRuntimeMetadata-to-DB asset migration:
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
                ensureBitcoinLikeProfiles(jdbc);
                for (ChainType chainType : List.of(ChainType.BTC, ChainType.LTC, ChainType.DOGE, ChainType.BCH)) {
                    String chain = chainType.name();
                    String txHash = "migration" + UUID.randomUUID().toString().replace("-", "");
                    String address = "migration-address-" + chain.toLowerCase();
                    String accountId = "migration-account-" + chain.toLowerCase() + "-" + UUID.randomUUID();
                    BigDecimal amount = new BigDecimal("1.25000000");

                    repository.upsertUtxo(
                            chain, chain, txHash, 0, address, amount, 100L, txHash, 6, false);

                    List<UtxoTransaction> spendable = repository.listSpendableUtxos(chain, chain, 1, 10, 0);
                    UtxoTransaction selected = spendable.stream()
                            .filter(utxo -> txHash.equals(utxo.getTxId()) && utxo.getSeq() == 0)
                            .findFirst()
                            .orElseThrow();
                    assertEquals(amount.setScale(18), selected.getBalance());
                    assertEquals(runtimeCurrencyId(jdbc, chain), selected.getCurrency());

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
                            amount, 101L, depositTx, 6, null, "{}");
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

                    long userId = 70_000L + chainType.ordinal();
                    assertTrue(repository.findMaxChainAddressIndex(
                            chain, chain, userId, 1, "DEPOSIT").isEmpty());
                    repository.upsertChainAddress(ChainAddressRecord.builder()
                            .chain(chain)
                            .assetSymbol(chain)
                            .accountId(Long.toString(userId))
                            .userId(userId)
                            .biz(1)
                            .addressIndex(0L)
                            .address("migration-address-" + chain.toLowerCase() + "-0")
                            .derivationPath("m/44/" + bip44CoinType(chain) + "/1/" + userId + "/0")
                            .walletRole("DEPOSIT")
                            .enabled(true)
                            .build());
                    repository.upsertChainAddress(ChainAddressRecord.builder()
                            .chain(chain)
                            .assetSymbol(chain)
                            .accountId(Long.toString(userId))
                            .userId(userId)
                            .biz(1)
                            .addressIndex(1L)
                            .address("migration-address-" + chain.toLowerCase() + "-1")
                            .derivationPath("m/44/" + bip44CoinType(chain) + "/1/" + userId + "/1")
                            .walletRole("DEPOSIT")
                            .enabled(true)
                            .build());
                    assertEquals(1L, repository.findMaxChainAddressIndex(
                                    chain, chain, userId, 1, "DEPOSIT")
                            .orElseThrow());
                    assertTrue(repository.findChainAddressByAddress(
                            chain, "migration-address-" + chain.toLowerCase() + "-1").isPresent());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int runtimeCurrencyId(JdbcTemplate jdbc, String chain) {
        Integer id = jdbc.queryForObject("""
                select min(runtime_currency_id)
                from chain_profile
                where chain = ? and enabled = true
                """, Integer.class, chain);
        assertNotNull(id, "missing enabled chain_profile for " + chain);
        return id;
    }

    private static void ensureBitcoinLikeProfiles(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into chain_profile(chain, network, family, runtime_currency_id, bip44_coin_type,
                                          native_symbol, deposit_confirmations, withdraw_confirmations,
                                          default_fee_rate, dust_threshold, enabled)
                values
                    ('BTC', 'testnet3', 'bitcoin-like', 1, 0, 'BTC', 1, 6, 2, 546, true),
                    ('LTC', 'testnet', 'bitcoin-like', 24, 2, 'LTC', 1, 6, 2, 1000, true),
                    ('DOGE', 'regtest', 'bitcoin-like', 41, 3, 'DOGE', 6, 6, 1000, 1000000, true),
                    ('BCH', 'regtest', 'bitcoin-like', 42, 145, 'BCH', 6, 6, 1, 546, true)
                on conflict (chain, network) do update set
                    runtime_currency_id = excluded.runtime_currency_id,
                    bip44_coin_type = excluded.bip44_coin_type,
                    native_symbol = excluded.native_symbol,
                    deposit_confirmations = excluded.deposit_confirmations,
                    withdraw_confirmations = excluded.withdraw_confirmations,
                    default_fee_rate = excluded.default_fee_rate,
                    dust_threshold = excluded.dust_threshold,
                    enabled = excluded.enabled,
                    updated_at = now()
                """);
        jdbc.update("""
                insert into chain_asset(chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw)
                values
                    ('BTC', 'BTC', 'NATIVE', 8, true, true, 546, 546),
                    ('LTC', 'LTC', 'NATIVE', 8, true, true, 100000, 100000),
                    ('DOGE', 'DOGE', 'NATIVE', 8, true, true, 1000000, 1000000),
                    ('BCH', 'BCH', 'NATIVE', 8, true, true, 546, 546)
                on conflict (chain, symbol) do update set
                    decimals = excluded.decimals,
                    native_asset = excluded.native_asset,
                    active = excluded.active,
                    min_transfer = excluded.min_transfer,
                    min_withdraw = excluded.min_withdraw,
                    updated_at = now()
                """);
    }

    private static int bip44CoinType(String chain) {
        return switch (chain) {
            case "BTC" -> 0;
            case "LTC" -> 2;
            case "DOGE" -> 3;
            case "BCH" -> 145;
            default -> throw new IllegalArgumentException(chain);
        };
    }
}

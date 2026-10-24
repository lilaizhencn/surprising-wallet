package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code BitcoinLikeUnifiedUtxoRuntimeMigrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class BitcoinLikeUnifiedUtxoRuntimeMigrationTest {
    /**
     * 验证 {@code unifiedUtxoRecordMustSupportSelectionLockReleaseSpendAndLedgerIdempotency} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void unifiedUtxoRecordMustSupportSelectionLockReleaseSpendAndLedgerIdempotency() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("utxo.migration.db.enabled"),
                "set -Dutxo.migration.db.enabled=true for PostgreSQL-backed UTXO migration validation");

        DriverManagerDataSource dataSource = com.surprising.wallet.custody.CustodyIntegrationDatabase.dataSource();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainRepositoryFixture repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);

            try {
                ensureBitcoinLikeProfiles(jdbc);
                UUID tenant = UUID.randomUUID();
                jdbc.update("insert into custody_tenant(id, slug, name, derivation_namespace) values (?, ?, 'UTXO DB flow', nextval('custody_derivation_namespace_seq'))",
                        tenant, "utxo-db-" + tenant);
                for (ChainType chainType : List.of(ChainType.BTC, ChainType.LTC, ChainType.DOGE, ChainType.BCH)) {
                    String chain = chainType.name();
                    var profile = repository.chainProfileRepository.findAccountByChain(chain).orElseThrow();
                    int coinType = profile.getBip44CoinType();
                    String txHash = "migration" + UUID.randomUUID().toString().replace("-", "");
                    String address = "migration-address-" + chain.toLowerCase();
                    String accountId = "migration-account-" + chain.toLowerCase() + "-" + UUID.randomUUID();
                    BigDecimal amount = new BigDecimal("1.25000000");

                    repository.utxoRepository.upsert(chain, chain, txHash, 0, address, amount, 100L, txHash, 6, false);

                    List<UtxoTransaction> spendable = repository.utxoRepository.listSpendable(
                            chain,
                            chain,
                            1,
                            10,
                            0,
                            profile.getRuntimeCurrencyId());
                    UtxoTransaction selected = spendable.stream()
                            .filter(utxo -> txHash.equals(utxo.getTxId()) && utxo.getSeq() == 0)
                            .findFirst()
                            .orElseThrow();
                    assertEquals(amount.setScale(18), selected.getBalance());
                    assertEquals(runtimeCurrencyId(jdbc, chain), selected.getCurrency());

                    String lockRef = "migration-lock-" + UUID.randomUUID();
                    assertEquals(1, repository.utxoRepository.lock(chain, txHash, 0, lockRef));
                    assertEquals(0, repository.utxoRepository.lock(chain, txHash, 0, lockRef + "-duplicate"));
                    assertEquals(1, repository.utxoRepository.release(chain, lockRef));
                    assertEquals(1, repository.utxoRepository.lock(chain, txHash, 0, lockRef));
                    assertEquals(1, repository.utxoRepository.markSpent(chain, lockRef, txHash + "-spent"));
                    assertEquals(0, repository.utxoRepository.release(chain, lockRef));

                    String depositTx = "deposit" + UUID.randomUUID().toString().replace("-", "");
                    repository.chainAddressRepository.upsert(ChainAddressRecord.builder()
                            .tenantId(tenant).chain(chain).assetSymbol(chain).accountId(accountId)
                            .userId(60000L + chainType.ordinal()).biz(1).addressIndex(0L).address(address)
                            .derivationPath("m/44/" + coinType + "/1/" + (60000L + chainType.ordinal()) + "/0")
                            .walletRole("DEPOSIT").enabled(true).build());
                    com.surprising.wallet.testutil.DepositTestSupport.attachCustodyAddress(jdbc, tenant, chain, chain, address);
                    DepositEvent event = new DepositEvent(
                            chainType, chain, depositTx, null, address,
                            amount, 101L, depositTx, 6, null, "{}");
                    assertTrue(repository.depositService.recordAndCredit(event, 0, 6, accountId));
                    assertFalse(repository.depositService.recordAndCredit(event, 0, 6, accountId));
                    assertTrue(repository.depositRecordRepository.existsCanonical(chain, depositTx, 0));
                    assertEquals(amount.setScale(18), jdbc.queryForObject("""
                            select total_balance from ledger_balance
                            where chain=? and asset_symbol=? and account_id=?
                            """, BigDecimal.class, chain, chain, accountId));
                    assertEquals(1L, jdbc.queryForObject("""
                            select count(*) from deposit_record
                            where chain=? and tx_hash=? and log_index=0 and credited=true
                            """, Long.class, chain, depositTx));

                    long userId = 70_000L + chainType.ordinal();
                    assertTrue(repository.chainAddressRepository.findMaxIndex(chain, chain, userId, 1, "DEPOSIT").isEmpty());
                    repository.chainAddressRepository.upsert(ChainAddressRecord.builder()
                            .chain(chain)
                            .assetSymbol(chain)
                            .accountId(Long.toString(userId))
                            .userId(userId)
                            .biz(1)
                            .addressIndex(0L)
                            .address("migration-address-" + chain.toLowerCase() + "-0")
                            .derivationPath("m/44/" + coinType + "/1/" + userId + "/0")
                            .walletRole("DEPOSIT")
                            .enabled(true)
                            .build());
                    repository.chainAddressRepository.upsert(ChainAddressRecord.builder()
                            .chain(chain)
                            .assetSymbol(chain)
                            .accountId(Long.toString(userId))
                            .userId(userId)
                            .biz(1)
                            .addressIndex(1L)
                            .address("migration-address-" + chain.toLowerCase() + "-1")
                            .derivationPath("m/44/" + coinType + "/1/" + userId + "/1")
                            .walletRole("DEPOSIT")
                            .enabled(true)
                            .build());
                    assertEquals(1L, repository.chainAddressRepository.findMaxIndex(chain, chain, userId, 1, "DEPOSIT")
                            .orElseThrow());
                    assertTrue(repository.chainAddressRepository.findEnabledByAddress(chain, "migration-address-" + chain.toLowerCase() + "-1").isPresent());
                }
            } finally {
                connection.rollback();
            }
        }
    }



    /**
     * 验证 {@code runtimeCurrencyId} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static int runtimeCurrencyId(JdbcTemplate jdbc, String chain) {
        Integer id = jdbc.queryForObject("""
                select min(runtime_currency_id)
                from chain_profile
                where chain = ? and enabled = true
                """, Integer.class, chain);
        assertNotNull(id, "missing enabled chain_profile for " + chain);
        return id;
    }

    /**
     * 验证 {@code ensureBitcoinLikeProfiles} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void ensureBitcoinLikeProfiles(JdbcTemplate jdbc) {
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        for (ChainType type : List.of(ChainType.BTC, ChainType.LTC, ChainType.DOGE, ChainType.BCH)) {
            var profile = profiles.findAccountByChain(type.name()).orElseThrow();
            jdbc.update("update chain_profile set enabled=false where chain=? and id<>?", type.name(), profile.getId());
            jdbc.update("update chain_profile set enabled=true where id=?", profile.getId());
            jdbc.update("update chain_asset set active=true where chain=? and symbol=? and native_asset=true",
                    type.name(), profile.getNativeSymbol());
        }
    }

}

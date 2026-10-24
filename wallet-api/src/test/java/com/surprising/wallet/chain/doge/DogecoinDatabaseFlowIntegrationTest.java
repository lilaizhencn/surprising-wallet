package com.surprising.wallet.chain.doge;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
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
 * 验证 {@code DogecoinDatabaseFlowIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class DogecoinDatabaseFlowIntegrationTest {
    /**
     * 验证 {@code syntheticFlowMustRemainIdempotentAndNonNegative} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void syntheticFlowMustRemainIdempotentAndNonNegative() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("doge.db.enabled"),
                "set -Ddoge.db.enabled=true for PostgreSQL-backed DOGE flow validation");
        DriverManagerDataSource dataSource = com.surprising.wallet.custody.CustodyIntegrationDatabase.dataSource();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainRepositoryFixture repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
            UUID tenant = UUID.randomUUID();
            jdbc.update("insert into custody_tenant(id, slug, name, derivation_namespace) values (?, ?, 'DB flow', nextval('custody_derivation_namespace_seq'))",
                    tenant, "doge-db-" + tenant);

            for (ChainType chainType : List.of(ChainType.DOGE, ChainType.BCH)) {
                String chain = chainType.name();
                String txid = UUID.randomUUID().toString().replace("-", "") + "00";
                String account = chain.toLowerCase() + "-test-" + UUID.randomUUID();
                repository.chainAddressRepository.upsert(com.surprising.wallet.common.chain.ChainAddressRecord.builder()
                        .tenantId(tenant).chain(chain).assetSymbol(chain).accountId(account)
                        .userId(60000L + chainType.ordinal()).biz(1).addressIndex(0L).address("synthetic")
                        .derivationPath("m/44/" + (chainType == ChainType.DOGE ? 3 : 145) + "/1/" + (60000L + chainType.ordinal()) + "/0")
                        .walletRole("DEPOSIT").enabled(true).build());
                com.surprising.wallet.testutil.DepositTestSupport.attachCustodyAddress(jdbc, tenant, chain, chain, "synthetic");
                DepositEvent event = new DepositEvent(
                        chainType, chain, txid, null, "synthetic",
                        new BigDecimal("25.00000000"), 1L, txid, 6, null, "{}");

                assertTrue(repository.depositService.recordAndCredit(event, 0, 6, account));
                assertFalse(repository.depositService.recordAndCredit(event, 0, 6, account));
                repository.utxoRepository.upsert(chain, chain, txid, 0, "synthetic", new BigDecimal("25.00000000"), 1L, txid, 6, true);
                assertEquals(1, repository.utxoRepository.lock(chain, txid, 0, "lock-1"));
                assertEquals(0, repository.utxoRepository.lock(chain, txid, 0, "lock-2"));
                assertEquals(1, repository.utxoRepository.release(chain, "lock-1"));

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
                repository.chainScanHeightRepository.upsert(chain, scannerName, 10L, 9L);
                repository.chainScanHeightRepository.upsert(chain, scannerName, 10L, 4L);
                assertEquals(4L, jdbc.queryForObject("""
                    select safe_height from chain_scan_height
                    where chain=? and scanner_name=?
                    """, Long.class, chain, scannerName));
                repository.chainScanHeightRepository.upsert(chain, scannerName, 9L, 8L);
                assertEquals(4L, jdbc.queryForObject("""
                    select safe_height from chain_scan_height
                    where chain=? and scanner_name=?
                    """, Long.class, chain, scannerName),
                        "a stale lower best height must not move the safe checkpoint");
            }
            connection.rollback();
        }
    }


}

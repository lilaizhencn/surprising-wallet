package com.surprising.wallet.chain.sui;

import com.surprising.wallet.testutil.AddressRuntimeTestSupport;
import com.surprising.wallet.chain.WalletKeyTestFixture;
import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code SuiDatabaseFlowIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class SuiDatabaseFlowIntegrationTest {
    /**
     * 验证 {@code recordsDepositsTransactionsAndGuardedLedgerOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recordsDepositsTransactionsAndGuardedLedgerOnce() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.db.enabled"),
                "set -Dsui.db.enabled=true for PostgreSQL-backed Sui validation");

        var dataSource = com.surprising.wallet.custody.CustodyIntegrationDatabase.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = SuiTenantIntegrationFixture.ensureTenant(jdbc);
        ChainRepositoryFixture repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
        SuiKeyService keys = new SuiKeyService(WalletKeyTestFixture.provider("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
        var addresses = new AddressRuntimeTestSupport(repository, WalletKeyTestFixture.provider("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
        var profile = addresses.runtime.requireProfile("SUI");

        long derivationIndex = 1_410_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
        ChainAddressRecord user = addresses.runtime.generateDepositAddressAtIndex(profile, 8101 + derivationIndex, 0, derivationIndex);
        user.setTenantId(tenantId);
        repository.chainAddressRepository.assignTenant(tenantId, user.getId());
        SuiTenantIntegrationFixture.attachDepositAddress(jdbc, user);
        String digest = "sui-db-" + UUID.randomUUID();
        DepositEvent event = new DepositEvent(ChainType.SUI, "SUI", digest,
                keys.address(derivationIndex + 1), user.getAddress(), new BigDecimal("100000000"),
                11L, digest, 1, null, "{}");

        assertTrue(repository.depositService.recordAndCredit(event, 0, 1, user.getAccountId()));
        assertFalse(repository.depositService.recordAndCredit(event, 0, 1, user.getAccountId()));
        assertTrue(repository.ledgerBalanceRepository.freeze("SUI", "SUI", user.getAccountId(), new BigDecimal("1"), tenantId));
        assertTrue(repository.ledgerBalanceRepository.release("SUI", "SUI", user.getAccountId(), new BigDecimal("1"), tenantId));

        repository.suiTransactionRepository.upsert(SuiTransactionRecord.builder()
                .chain("SUI")
                .txDigest(digest)
                .sender(event.fromAddress())
                .receiver(user.getAddress())
                .assetSymbol("SUI")
                .coinType(SuiRpcClient.SUI_COIN_TYPE)
                .amount(event.amount())
                .gasUsed(10L)
                .checkpoint(11L)
                .status("CONFIRMED")
                .rawPayload("{}")
                .build());
        repository.suiTransactionRepository.upsert(SuiTransactionRecord.builder()
                .chain("SUI")
                .txDigest(digest)
                .sender(event.fromAddress())
                .receiver(user.getAddress())
                .assetSymbol("SUI")
                .coinType(SuiRpcClient.SUI_COIN_TYPE)
                .amount(event.amount())
                .gasUsed(20L)
                .checkpoint(12L)
                .status("CONFIRMED")
                .rawPayload("{\"replay\":true}")
                .build());

        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from deposit_record where chain='SUI' and tx_hash=? and credited=true
                """, Long.class, digest));
        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from sui_transaction where chain='SUI' and tx_digest=?
                """, Long.class, digest));
        assertEquals(new BigDecimal("100000000.000000000000000000"),
                jdbc.queryForObject("""
                        select total_balance from ledger_balance
                        where chain='SUI' and asset_symbol='SUI' and account_id=?
                        """, BigDecimal.class, user.getAccountId()));
    }

}

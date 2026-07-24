package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiDatabaseFlowIntegrationTest {
    @Test
    void recordsDepositsTransactionsAndGuardedLedgerOnce() {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.db.enabled"),
                "set -Dsui.db.enabled=true for PostgreSQL-backed Sui validation");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SUI_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SUI_DB_USER", "wallet"),
                env("SUI_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = SuiTenantIntegrationFixture.ensureTenant(jdbc);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        SuiKeyService keys = new SuiKeyService("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        SuiAddressService addresses = new SuiAddressService(keys, repository);

        long derivationIndex = 1_410_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
        ChainAddressRecord user = addresses.createNativeAddress(tenantId, 8101 + derivationIndex, 0,
                derivationIndex, "DEPOSIT");
        String digest = "sui-db-" + UUID.randomUUID();
        DepositEvent event = new DepositEvent(ChainType.SUI, "SUI", digest,
                keys.address(derivationIndex + 1), user.getAddress(), new BigDecimal("100000000"),
                11L, digest, 1, null, "{}");

        assertTrue(repository.recordAndCreditDeposit(event, 0, 1, user.getAccountId()));
        assertFalse(repository.recordAndCreditDeposit(event, 0, 1, user.getAccountId()));
        assertTrue(repository.freezeLedgerBalance(
                tenantId, "SUI", "SUI", user.getAccountId(), new BigDecimal("1")));
        assertTrue(repository.releaseLockedBalance(
                tenantId, "SUI", "SUI", user.getAccountId(), new BigDecimal("1")));

        repository.recordSuiTransaction(SuiTransactionRecord.builder()
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
        repository.recordSuiTransaction(SuiTransactionRecord.builder()
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

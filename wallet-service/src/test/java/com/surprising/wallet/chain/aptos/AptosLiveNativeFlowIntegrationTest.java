package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosLiveNativeFlowIntegrationTest {
    private static final long OWNER_INDEX = 1_300_001L;
    private static final long EXTERNAL_INDEX = 1_300_002L;
    private static final long HOT_INDEX = 0L;
    private static final long ONE_APT = 100_000_000L;
    private static final BigDecimal DEPOSIT_AMOUNT = BigDecimal.ONE;
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("0.2");
    private static final BigDecimal COLLECTION_AMOUNT = new BigDecimal("0.3");

    @Test
    void liveAptDepositWithdrawCollectionAndReconciliationAreSafe() {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.live.enabled"),
                "set -Daptos.live.enabled=true and SW_ED25519_SEED for Aptos devnet live validation");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("APTOS_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("APTOS_DB_USER", "wallet"),
                env("APTOS_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        AptosRpcClient rpc = new AptosRpcClient(new ObjectMapper(),
                env("APTOS_RPC_URL", "https://fullnode.devnet.aptoslabs.com/v1"),
                env("APTOS_FAUCET_URL", "https://faucet.devnet.aptoslabs.com"));
        AptosKeyService keys = new AptosKeyService(masterSeed);
        AptosAddressService addresses = new AptosAddressService(keys, repository);
        AptosTransactionSigner signer = new AptosTransactionSigner(keys);
        AptosTransactionService transactions = new AptosTransactionService(rpc, signer, repository);
        AptosDepositScanner scanner = new AptosDepositScanner(rpc, repository);

        ChainAddressRecord owner = addresses.createNativeAddress(6001, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = external(keys);
        ChainAddressRecord hot = addresses.createNativeAddress(0, 0, HOT_INDEX, "DEPOSIT");
        AptosTenantIntegrationFixture.TenantAddress tenantAddress =
                AptosTenantIntegrationFixture.attachDepositAddress(jdbc, owner);
        AptosTenantIntegrationFixture.attachPlatformAddress(jdbc, hot);

        rpc.fundDevnetAccount(external.getAddress(), 3L * ONE_APT);
        waitForBalanceAtLeast(rpc, external.getAddress(), 3L * ONE_APT, Duration.ofMinutes(2));

        long checkpoint = rpc.ledgerVersion();
        repository.updateScanHeight("APTOS", AptosDepositScanner.SCANNER, checkpoint, checkpoint);
        String depositHash = transactions.sendNative(
                external, owner.getAddress(), ONE_APT);
        transactions.requireSuccessfulConfirmation(depositHash, Duration.ofMinutes(2));
        scanner.scanAndCredit();
        assertAmount(DEPOSIT_AMOUNT, ledger(jdbc, tenantAddress.tenantId(), owner.getAccountId()));
        scanner.scanAndCredit();
        assertAmount(DEPOSIT_AMOUNT, ledger(jdbc, tenantAddress.tenantId(), owner.getAccountId()));

        String withdrawOrder = "aptos-live-withdraw-" + UUID.randomUUID();
        assertEquals(1, repository.createTenantWithdrawalOrder(
                tenantAddress.tenantId(), withdrawOrder, owner.getUserId(), "APTOS", "APT",
                owner.getAddress(), owner.getAccountId(), external.getAddress(),
                WITHDRAW_AMOUNT, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(
                tenantAddress.tenantId(), "APTOS", "APT", owner.getAccountId(), WITHDRAW_AMOUNT));
        repository.updateWithdrawalStatus(
                tenantAddress.tenantId(), "APTOS", withdrawOrder,
                "FROZEN", owner.getAddress(), null, null);
        assertEquals(1, repository.claimWithdrawalSigning(
                tenantAddress.tenantId(), "APTOS", withdrawOrder, owner.getAddress()));
        String withdrawHash = transactions.sendNative(
                owner, external.getAddress(), toOctas(WITHDRAW_AMOUNT));
        assertEquals(1, repository.markWithdrawalSent(
                tenantAddress.tenantId(), "APTOS", withdrawOrder, owner.getAddress(), withdrawHash));
        assertTrue(transactions.confirmWithdrawal(
                tenantAddress.tenantId(), withdrawOrder, "APT",
                owner.getAccountId(), WITHDRAW_AMOUNT));

        String collectionNo = "aptos-live-collection-" + UUID.randomUUID();
        assertEquals(1, repository.createCollectionRecord(
                tenantAddress.tenantId(), tenantAddress.custodyAddressId(), collectionNo,
                "APTOS", "APT", owner.getAddress(), hot.getAddress(),
                COLLECTION_AMOUNT, BigDecimal.ZERO, null));
        String collectionHash = transactions.collectNative(
                tenantAddress.tenantId(), collectionNo, owner, hot.getAddress(),
                BigDecimal.valueOf(toOctas(COLLECTION_AMOUNT)));
        assertEquals(collectionHash, transactions.collectNative(
                tenantAddress.tenantId(), collectionNo, owner, hot.getAddress(),
                BigDecimal.valueOf(toOctas(COLLECTION_AMOUNT))));
        assertTrue(transactions.confirmCollection(tenantAddress.tenantId(), collectionNo));

        BigDecimal expectedLedger = DEPOSIT_AMOUNT.subtract(WITHDRAW_AMOUNT);
        assertAmount(expectedLedger, ledger(jdbc, tenantAddress.tenantId(), owner.getAccountId()));
        long actualGas = transactionGas(rpc, withdrawHash) + transactionGas(rpc, collectionHash);
        long controlled = rpc.aptBalance(owner.getAddress()) + rpc.aptBalance(hot.getAddress());
        assertEquals(toOctas(expectedLedger), Math.addExact(controlled, actualGas));

        assertEquals(2L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where tx_hash in (?, ?) and status = 'CONFIRMED'
                   and raw_payload is not null
                """, Long.class, withdrawHash, collectionHash));
        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where tx_hash = ? and status = 'CONFIRMED' and raw_payload is not null
                """, Long.class, depositHash));
        assertEquals(3L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where (tx_hash = ? and amount = 1)
                    or (tx_hash = ? and amount = 0.2)
                    or (tx_hash = ? and amount = 0.3)
                """, Long.class, depositHash, withdrawHash, collectionHash));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='APTOS'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("APTOS_OWNER=" + owner.getAddress());
        System.out.println("APTOS_EXTERNAL=" + external.getAddress());
        System.out.println("APTOS_HOT=" + hot.getAddress());
        System.out.println("APTOS_NATIVE_LEDGER=" + expectedLedger.toPlainString());
        System.out.println("APTOS_NATIVE_WITHDRAW_TX=" + withdrawHash);
        System.out.println("APTOS_NATIVE_COLLECTION_TX=" + collectionHash);
    }

    private static void waitForBalanceAtLeast(AptosRpcClient rpc, String address, long amount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.aptBalance(address) >= amount) {
                return;
            }
            sleep(1_000L);
        }
        throw new IllegalStateException("Aptos faucet balance did not arrive for " + address);
    }

    private static ChainAddressRecord external(AptosKeyService keys) {
        Ed25519DerivedKey key = keys.derive(6002L, 0, EXTERNAL_INDEX);
        String address = AptosKeyService.address(key.publicKey());
        return ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("APT")
                .accountId(address)
                .userId(6002L)
                .biz(0)
                .addressIndex(EXTERNAL_INDEX)
                .address(address)
                .ownerAddress(address)
                .derivationPath(key.derivationPath())
                .walletRole("EXTERNAL")
                .enabled(true)
                .build();
    }

    private static BigDecimal ledger(JdbcTemplate jdbc, UUID tenantId, String accountId) {
        return jdbc.queryForObject("""
                select coalesce(sum(total_balance), 0) from ledger_balance
                 where tenant_id = ? and chain = 'APTOS'
                   and asset_symbol = 'APT' and account_id = ?
                """, BigDecimal.class, tenantId, accountId);
    }

    private static long transactionGas(AptosRpcClient rpc, String hash) {
        var transaction = rpc.transactionByHash(hash);
        return Math.multiplyExact(
                transaction.path("gas_used").asLong(),
                transaction.path("gas_unit_price").asLong());
    }

    private static long toOctas(BigDecimal amount) {
        return amount.movePointRight(8).longValueExact();
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected.toPlainString() + " but was " + actual.toPlainString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aptos live wait interrupted", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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

    @Test
    void liveAptDepositWithdrawCollectionAreIdempotent() {
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
        ChainAddressRecord external = addresses.createNativeAddress(6002, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createNativeAddress(0, 0, HOT_INDEX, "DEPOSIT");

        rpc.fundDevnetAccount(owner.getAddress(), ONE_APT);
        waitForBalanceAtLeast(rpc, owner.getAddress(), ONE_APT, Duration.ofMinutes(2));

        scanner.scanAndCredit();
        BigDecimal beforeReplay = ledger(repository, "APT", owner.getAccountId()).getTotalBalance();
        scanner.scanAndCredit();
        assertEquals(beforeReplay, ledger(repository, "APT", owner.getAccountId()).getTotalBalance());

        String withdrawOrder = "aptos-live-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("10000000");
        String withdrawHash = transactions.withdrawNative(withdrawOrder, owner.getUserId(), owner,
                external.getAddress(), withdrawAmount);
        assertEquals(withdrawHash, transactions.withdrawNative(withdrawOrder, owner.getUserId(), owner,
                external.getAddress(), withdrawAmount));
        assertTrue(transactions.confirmWithdrawal(withdrawOrder, "APT", owner.getAccountId(),
                withdrawAmount.add(new BigDecimal("5000000"))));

        String collectionNo = "aptos-live-collection-" + UUID.randomUUID();
        BigDecimal collectionAmount = new BigDecimal("10000000");
        String collectionHash = transactions.collectNative(null, collectionNo, owner, hot.getAddress(), collectionAmount);
        assertEquals(collectionHash, transactions.collectNative(null, collectionNo, owner, hot.getAddress(),
                collectionAmount));
        assertTrue(transactions.confirmCollection(null, collectionNo));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='APTOS'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("APTOS_OWNER=" + owner.getAddress());
        System.out.println("APTOS_EXTERNAL=" + external.getAddress());
        System.out.println("APTOS_HOT=" + hot.getAddress());
        System.out.println("APTOS_NATIVE_DEPOSIT_BALANCE_OCTAS=" + beforeReplay.toPlainString());
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

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance("APTOS", symbol, accountId).orElseThrow();
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

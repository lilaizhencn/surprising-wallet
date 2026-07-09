package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosLiveTokenFlowIntegrationTest {
    private static final String SYMBOL = "MUSD";
    private static final long PUBLISHER_INDEX = 1_300_010L;
    private static final long OWNER_INDEX = 1_300_011L;
    private static final long EXTERNAL_INDEX = 1_300_012L;
    private static final long HOT_INDEX = 0L;
    private static final long ONE_APT = 100_000_000L;

    @Test
    void liveMockCoinDepositWithdrawCollectionAreIdempotent() {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.token.live.enabled"),
                "set -Daptos.token.live.enabled=true and SW_ED25519_SEED for Aptos devnet token validation");
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

        String publisher = keys.address(PUBLISHER_INDEX);
        String coinType = env("APTOS_TEST_COIN_TYPE", publisher + "::mock_coin::MockCoin");
        upsertToken(jdbc, coinType);

        ChainAddressRecord owner = addresses.createCoinAddress(SYMBOL, 6011, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = addresses.createCoinAddress(SYMBOL, 6012, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createNativeAddress(0, 0, HOT_INDEX, "DEPOSIT");

        fundAndWait(rpc, publisher, ONE_APT);
        fundAndWait(rpc, owner.getAddress(), ONE_APT);

        long mintAmount = 10_000_000L;
        String mintHash = transactions.runEntryFunction(PUBLISHER_INDEX, publisher, moduleOf(coinType), "mint",
                List.of(), List.of(
                        AptosTransactionSigner.FunctionArgument.address(owner.getAddress()),
                        AptosTransactionSigner.FunctionArgument.u64(mintAmount)));
        JsonNode mintTransaction = transactions.requireSuccessfulConfirmation(mintHash, Duration.ofMinutes(2));

        long checkpoint = Math.max(0L, mintTransaction.path("version").asLong() - 1L);
        repository.updateScanHeight("APTOS", "aptos-coin-event-scanner", checkpoint, checkpoint);
        scanner.scanAndCredit();
        BigDecimal beforeReplay = ledger(repository, owner.getAccountId()).getTotalBalance();
        assertTrue(beforeReplay.compareTo(BigDecimal.valueOf(mintAmount)) >= 0);
        scanner.scanAndCredit();
        assertEquals(beforeReplay, ledger(repository, owner.getAccountId()).getTotalBalance());

        String withdrawOrder = "aptos-live-token-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("1000000");
        String withdrawHash = transactions.withdrawCoin(withdrawOrder, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount);
        assertEquals(withdrawHash, transactions.withdrawCoin(withdrawOrder, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount));
        assertTrue(transactions.confirmWithdrawal(withdrawOrder, SYMBOL, owner.getAccountId(), withdrawAmount));

        String collectionNo = "aptos-live-token-collection-" + UUID.randomUUID();
        BigDecimal collectionAmount = new BigDecimal("1000000");
        String collectionHash = transactions.collectCoin(collectionNo, owner, coinType, hot.getAddress(),
                collectionAmount);
        assertEquals(collectionHash, transactions.collectCoin(collectionNo, owner, coinType,
                hot.getAddress(), collectionAmount));
        assertTrue(transactions.confirmCollection(collectionNo));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='APTOS'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("APTOS_TOKEN_PUBLISHER=" + publisher);
        System.out.println("APTOS_TOKEN_OWNER=" + owner.getAddress());
        System.out.println("APTOS_TOKEN_EXTERNAL=" + external.getAddress());
        System.out.println("APTOS_TOKEN_HOT=" + hot.getAddress());
        System.out.println("APTOS_TOKEN_COIN_TYPE=" + coinType);
        System.out.println("APTOS_TOKEN_MINT_TX=" + mintHash);
        System.out.println("APTOS_TOKEN_WITHDRAW_TX=" + withdrawHash);
        System.out.println("APTOS_TOKEN_COLLECTION_TX=" + collectionHash);
    }

    private static void fundAndWait(AptosRpcClient rpc, String address, long amount) {
        if (rpc.coinBalance(address, AptosRpcClient.aptCoinType()) < amount / 2) {
            rpc.fundDevnetAccount(address, amount);
        }
        waitForBalanceAtLeast(rpc, address, amount / 2, Duration.ofMinutes(2));
    }

    private static void waitForBalanceAtLeast(AptosRpcClient rpc, String address, long amount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.coinBalance(address, AptosRpcClient.aptCoinType()) >= amount) {
                return;
            }
            sleep(1_000L);
        }
        throw new IllegalStateException("Aptos faucet balance did not arrive for " + address);
    }

    private static void upsertToken(JdbcTemplate jdbc, String coinType) {
        jdbc.update("""
                insert into token_config(
                    chain, network, symbol, standard, token_standard, contract_address,
                    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
                    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
                    confirmation_required
                )
                values ('APTOS', 'devnet', ?, 'APTOS_COIN', 'COIN', ?, 6, true,
                        1, 1, 1, 1, true, 1, 'APT_GAS', 1)
                on conflict (chain, symbol) do update
                set network = excluded.network,
                    standard = excluded.standard,
                    token_standard = excluded.token_standard,
                    contract_address = excluded.contract_address,
                    decimals = excluded.decimals,
                    enabled = true,
                    min_deposit = excluded.min_deposit,
                    min_withdraw = excluded.min_withdraw,
                    min_deposit_amount = excluded.min_deposit_amount,
                    min_withdraw_amount = excluded.min_withdraw_amount,
                    collect_enabled = excluded.collect_enabled,
                    collect_threshold = excluded.collect_threshold,
                    gas_strategy = excluded.gas_strategy,
                    confirmation_required = excluded.confirmation_required,
                    updated_at = now()
                """, SYMBOL, coinType);
    }

    private static String moduleOf(String coinType) {
        String[] parts = coinType.split("::");
        if (parts.length != 3) {
            throw new IllegalArgumentException("coin type must be <address>::<module>::<struct>");
        }
        return parts[0] + "::" + parts[1];
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String accountId) {
        return repository.findLedgerBalance("APTOS", SYMBOL, accountId).orElseThrow();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aptos live token wait interrupted", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

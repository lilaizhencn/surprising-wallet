package com.surprising.wallet.service.chain.sui;

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

class SuiLiveTokenFlowIntegrationTest {
    private static final String SYMBOL = "MUSD";
    private static final long OWNER_INDEX = 1_400_001L;
    private static final long EXTERNAL_INDEX = 1_400_002L;
    private static final long HOT_INDEX = 0L;

    @Test
    void liveSuiCoinDepositWithdrawCollectionAreIdempotent() {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.token.live.enabled"),
                "set -Dsui.token.live.enabled=true, SW_ED25519_SEED and SUI_MOCK_COIN_TYPE");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        String coinType = System.getenv("SUI_MOCK_COIN_TYPE");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");
        Assumptions.assumeTrue(coinType != null && !coinType.isBlank(), "SUI_MOCK_COIN_TYPE is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SUI_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SUI_DB_USER", "wallet"),
                env("SUI_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        upsertToken(jdbc, coinType);

        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        SuiRpcClient rpc = new SuiRpcClient(new ObjectMapper(),
                env("SUI_RPC_URL", "https://fullnode.testnet.sui.io:443"));
        SuiKeyService keys = new SuiKeyService(masterSeed);
        SuiAddressService addresses = new SuiAddressService(keys, repository);
        SuiTransactionSigner signer = new SuiTransactionSigner(keys);
        SuiTransactionService transactions = new SuiTransactionService(rpc, signer, repository);
        SuiDepositScanner scanner = new SuiDepositScanner(rpc, repository);

        ChainAddressRecord owner = addresses.createCoinAddress(SYMBOL, 7001, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = addresses.createCoinAddress(SYMBOL, 7002, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createNativeAddress(0, 0, HOT_INDEX, "DEPOSIT");

        waitForBalanceAtLeast(rpc, owner.getAddress(), coinType, 10_000_000L, Duration.ofMinutes(2));
        scanner.scanAndCredit();
        BigDecimal beforeReplay = ledger(repository, owner.getAccountId()).getTotalBalance();
        assertTrue(beforeReplay.compareTo(new BigDecimal("10000000")) >= 0);
        scanner.scanAndCredit();
        assertEquals(beforeReplay, ledger(repository, owner.getAccountId()).getTotalBalance());

        String withdrawOrder = "sui-token-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("2000000");
        String withdrawDigest = transactions.withdrawCoin(withdrawOrder, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount);
        assertEquals(withdrawDigest, transactions.withdrawCoin(withdrawOrder, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount));
        assertTrue(transactions.confirmWithdrawal(withdrawOrder, SYMBOL, owner.getAccountId(), withdrawAmount));

        String collectionNo = "sui-token-collection-" + UUID.randomUUID();
        BigDecimal collectionAmount = new BigDecimal("3000000");
        String collectionDigest = transactions.collectCoin(collectionNo, owner, coinType, hot.getAddress(),
                collectionAmount);
        assertEquals(collectionDigest, transactions.collectCoin(collectionNo, owner, coinType, hot.getAddress(),
                collectionAmount));
        assertTrue(transactions.confirmCollection(collectionNo));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='SUI'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("SUI_MUSD_OWNER=" + owner.getAddress());
        System.out.println("SUI_MUSD_EXTERNAL=" + external.getAddress());
        System.out.println("SUI_MUSD_HOT=" + hot.getAddress());
        System.out.println("SUI_MUSD_COIN_TYPE=" + coinType);
        System.out.println("SUI_MUSD_MINT_TX=" + env("SUI_MOCK_MINT_TX", ""));
        System.out.println("SUI_MUSD_WITHDRAW_TX=" + withdrawDigest);
        System.out.println("SUI_MUSD_COLLECTION_TX=" + collectionDigest);
    }

    private static void upsertToken(JdbcTemplate jdbc, String coinType) {
        jdbc.update("""
                insert into token_config(
                    chain, network, symbol, standard, token_standard, contract_address,
                    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
                    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
                    confirmation_required
                )
                values ('SUI', 'testnet', ?, 'SUI_COIN', 'COIN', ?, 6, true,
                        1, 1, 1, 1, true, 1, 'SUI_GAS_OBJECT', 1)
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

    private static void waitForBalanceAtLeast(SuiRpcClient rpc, String address, String coinType,
                                              long amount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.balance(address, coinType).compareTo(BigDecimal.valueOf(amount)) >= 0) {
                return;
            }
            sleep(1_000L);
        }
        throw new IllegalStateException("Sui token balance did not arrive for " + address);
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String accountId) {
        return repository.findLedgerBalance("SUI", SYMBOL, accountId).orElseThrow();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui live token wait interrupted", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

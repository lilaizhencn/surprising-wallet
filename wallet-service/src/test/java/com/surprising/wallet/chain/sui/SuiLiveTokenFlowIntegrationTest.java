package com.surprising.wallet.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiLiveTokenFlowIntegrationTest {
    private static final String CHAIN = "SUI";
    private static final String SYMBOL = "USDC";
    private static final int DECIMALS = 6;
    private static final long MINT_ATOMIC = 10_000_000L;
    private static final long WITHDRAW_ATOMIC = 1_250_000L;
    private static final long COLLECTION_ATOMIC = 2_000_000L;
    private static final long RUN_INDEX = 1_700_000L
            + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L) * 3L;
    private static final long OWNER_INDEX = RUN_INDEX + 1L;
    private static final long EXTERNAL_INDEX = RUN_INDEX + 2L;
    private static final long HOT_INDEX = RUN_INDEX;

    @Test
    void liveUsdcDepositWithdrawAndReplayAreExact() {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.token.live.enabled"),
                "set -Dsui.token.live.enabled=true with local package and treasury IDs");
        String masterSeed = requiredEnv("SW_ED25519_SEED");
        String packageId = requiredEnv("SUI_MOCK_PACKAGE_ID");
        String treasuryId = requiredEnv("SUI_MOCK_TREASURY_ID");
        String coinType = SuiHex.normalizeAddress(packageId) + "::usdc::USDC";

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SUI_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SUI_DB_USER", "wallet"),
                env("SUI_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = SuiTenantIntegrationFixture.ensureTenant(jdbc);
        configureToken(jdbc, coinType);

        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        SuiRpcClient rpc = new SuiRpcClient(new ObjectMapper(),
                env("SUI_GRPC_ENDPOINT", "fullnode.testnet.sui.io:443"));
        SuiKeyService keys = new SuiKeyService(masterSeed);
        SuiAddressService addresses = new SuiAddressService(keys, repository);
        SuiTransactionService transactions = new SuiTransactionService(
                rpc, new SuiTransactionSigner(keys), repository);
        SuiDepositScanner scanner = new SuiDepositScanner(rpc, repository);

        ChainAddressRecord owner = addresses.createCoinAddress(
                tenantId, SYMBOL, 7201, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = addresses.createCoinAddress(
                tenantId, SYMBOL, 7202, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createCoinAddress(
                tenantId, SYMBOL, 7200, 0, HOT_INDEX, "DEPOSIT");
        UUID custodyAddressId = SuiTenantIntegrationFixture.attachDepositAddress(jdbc, owner);

        if (rpc.balance(owner.getAddress(), SuiRpcClient.SUI_COIN_TYPE)
                .compareTo(new BigDecimal("100000000")) < 0) {
            fundGas(owner.getAddress());
            waitForBalance(rpc, owner.getAddress(), SuiRpcClient.SUI_COIN_TYPE,
                    new BigDecimal("100000000"), Duration.ofMinutes(2));
        }

        long beforeMintCheckpoint = rpc.latestCheckpoint();
        repository.updateScanHeight(CHAIN, SuiDepositScanner.SCANNER,
                beforeMintCheckpoint, beforeMintCheckpoint);
        String mintDigest = mint(packageId, treasuryId, owner.getAddress(), MINT_ATOMIC);
        transactions.requireSuccessfulConfirmation(mintDigest, Duration.ofMinutes(2));
        waitForBalance(rpc, owner.getAddress(), coinType,
                BigDecimal.valueOf(MINT_ATOMIC), Duration.ofMinutes(2));

        scanner.scanAndCredit();
        BigDecimal depositAmount = display(MINT_ATOMIC);
        assertAmountEquals(depositAmount, ledger(repository, owner.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmountEquals(depositAmount, ledger(repository, owner.getAccountId()).getTotalBalance());

        BigDecimal withdrawAmount = display(WITHDRAW_ATOMIC);
        String withdrawalNo = "sui-usdc-withdraw-" + UUID.randomUUID();
        String withdrawalDigest = transactions.withdrawCoin(
                tenantId, withdrawalNo, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount);
        assertEquals(withdrawalDigest, transactions.withdrawCoin(
                tenantId, withdrawalNo, owner.getUserId(), owner,
                coinType, external.getAddress(), withdrawAmount));
        assertTrue(transactions.confirmWithdrawal(
                tenantId, withdrawalNo, SYMBOL, owner.getAccountId(), withdrawAmount));

        String collectionNo = "sui-usdc-collection-" + UUID.randomUUID();
        BigDecimal collectionAmount = display(COLLECTION_ATOMIC);
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, CHAIN, SYMBOL,
                owner.getAddress(), hot.getAddress(), collectionAmount, BigDecimal.ZERO, null));
        String collectionDigest = transactions.collectCoin(
                tenantId, collectionNo, owner, coinType, hot.getAddress(),
                BigDecimal.valueOf(COLLECTION_ATOMIC));
        assertEquals(collectionDigest, transactions.collectCoin(
                tenantId, collectionNo, owner, coinType, hot.getAddress(),
                BigDecimal.valueOf(COLLECTION_ATOMIC)));
        assertTrue(transactions.confirmCollection(tenantId, collectionNo));

        assertAmountEquals(depositAmount.subtract(withdrawAmount),
                ledger(repository, owner.getAccountId()).getTotalBalance());
        assertAmountEquals(BigDecimal.ZERO, ledger(repository, owner.getAccountId()).getLockedBalance());
        assertAmountEquals(BigDecimal.valueOf(MINT_ATOMIC - WITHDRAW_ATOMIC - COLLECTION_ATOMIC),
                rpc.balance(owner.getAddress(), coinType));
        assertAmountEquals(BigDecimal.valueOf(WITHDRAW_ATOMIC),
                rpc.balance(external.getAddress(), coinType));
        assertAmountEquals(BigDecimal.valueOf(COLLECTION_ATOMIC),
                rpc.balance(hot.getAddress(), coinType));
        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                 where chain='SUI'
                   and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("SUI_USDC_TYPE=" + coinType);
        System.out.println("SUI_USDC_OWNER=" + owner.getAddress());
        System.out.println("SUI_USDC_MINT_TX=" + mintDigest);
        System.out.println("SUI_USDC_WITHDRAW_TX=" + withdrawalDigest);
        System.out.println("SUI_USDC_COLLECTION_TX=" + collectionDigest);
    }

    private static void configureToken(JdbcTemplate jdbc, String coinType) {
        assertEquals(1, jdbc.update("""
                update token_config
                   set network='localnet',
                       standard='SUI_COIN',
                       token_standard='COIN',
                       contract_address=?,
                       decimals=6,
                       enabled=true,
                       updated_at=now()
                 where chain='SUI' and symbol='USDC' and enabled=true
                """, coinType));
        jdbc.update("""
                insert into chain_asset(chain,symbol,asset_kind,contract_address,decimals,native_asset,active,
                                        min_transfer,min_withdraw)
                values('SUI','USDC','SUI_COIN',?,6,false,true,0,0)
                on conflict(chain,symbol) do update set
                    asset_kind=excluded.asset_kind,
                    contract_address=excluded.contract_address,
                    decimals=excluded.decimals,
                    native_asset=false,
                    active=true,
                    updated_at=now()
                """, coinType);
    }

    private static String mint(String packageId, String treasuryId, String recipient, long amount) {
        List<String> command = new ArrayList<>(List.of(
                env("SUI_CLI_BIN", "sui"), "client", "--client.config", requiredEnv("SUI_CLIENT_CONFIG"),
                "call", "--package", packageId, "--module", "usdc", "--function", "mint",
                "--args", treasuryId, Long.toString(amount), recipient,
                "--gas-budget", "100000000", "--json"));
        JsonNode result = commandJson(command, "Sui mock USDC mint");
        assertEquals("success", result.path("effects").path("status").path("status").asText());
        return result.path("digest").asText();
    }

    private static void fundGas(String recipient) {
        String body = "{\"FixedAmountRequest\":{\"recipient\":\""
                + SuiHex.normalizeAddress(recipient) + "\"}}";
        JsonNode result = commandJson(List.of(
                "curl", "-fsS", "-X", "POST",
                env("SUI_FAUCET_URL", "http://127.0.0.1:9123/v2/gas"),
                "-H", "content-type: application/json", "-d", body), "Sui local faucet");
        assertTrue(result.path("coins_sent").isArray());
    }

    private static JsonNode commandJson(List<String> command, String operation) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(operation + " failed: " + output);
            }
            return new ObjectMapper().readTree(output);
        } catch (IOException e) {
            throw new IllegalStateException(operation + " IO failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", e);
        }
    }

    private static void waitForBalance(SuiRpcClient rpc, String owner, String coinType,
                                       BigDecimal minimum, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.balance(owner, coinType).compareTo(minimum) >= 0) {
                return;
            }
            sleep(500L);
        }
        throw new IllegalStateException("Sui balance did not arrive for " + owner + " " + coinType);
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String accountId) {
        return repository.findLedgerBalance(CHAIN, SYMBOL, accountId).orElseThrow();
    }

    private static BigDecimal display(long atomic) {
        return BigDecimal.valueOf(atomic).movePointLeft(DECIMALS);
    }

    private static void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected amount " + expected.toPlainString()
                        + " but was " + actual.toPlainString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui live wait interrupted", e);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

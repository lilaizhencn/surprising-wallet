package com.surprising.wallet.chain.sui;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiLiveNativeFlowIntegrationTest {
    private static final long RUN_INDEX = 1_400_000L
            + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L) * 3L;
    private static final long OWNER_INDEX = RUN_INDEX + 1L;
    private static final long EXTERNAL_INDEX = RUN_INDEX + 2L;
    private static final long HOT_INDEX = 0L;
    private static final long ONE_SUI = 1_000_000_000L;
    private static final long COLLECTION_MIST = 200_000_000L;

    @Test
    void liveSuiDepositWithdrawAreIdempotent() {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.live.enabled"),
                "set -Dsui.live.enabled=true and SW_ED25519_SEED for Sui testnet live validation");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SUI_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SUI_DB_USER", "wallet"),
                env("SUI_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = SuiTenantIntegrationFixture.ensureTenant(jdbc);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        SuiRpcClient rpc = new SuiRpcClient(new ObjectMapper(),
                env("SUI_GRPC_ENDPOINT", "fullnode.testnet.sui.io:443"));
        SuiKeyService keys = new SuiKeyService(masterSeed);
        SuiAddressService addresses = new SuiAddressService(keys, repository);
        SuiTransactionSigner signer = new SuiTransactionSigner(keys);
        SuiTransactionService transactions = new SuiTransactionService(rpc, signer, repository);
        SuiDepositScanner scanner = new SuiDepositScanner(rpc, repository);

        ChainAddressRecord owner = addresses.createNativeAddress(
                tenantId, 7001, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = addresses.createNativeAddress(
                tenantId, 7002, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createNativeAddress(
                tenantId, 0, 0, HOT_INDEX, "DEPOSIT");
        UUID custodyAddressId = SuiTenantIntegrationFixture.attachDepositAddress(jdbc, owner);

        String faucetDigest = "existing-balance";
        if (rpc.balance(owner.getAddress(), SuiRpcClient.SUI_COIN_TYPE).compareTo(BigDecimal.valueOf(ONE_SUI)) < 0) {
            faucetDigest = fund(owner.getAddress());
            waitForBalanceAtLeast(rpc, owner.getAddress(), ONE_SUI, Duration.ofMinutes(2));
        }

        scanner.scanAndCredit();
        BigDecimal beforeReplay = ledger(repository, owner.getAccountId()).getTotalBalance();
        assertTrue(beforeReplay.compareTo(BigDecimal.ONE) >= 0);
        scanner.scanAndCredit();
        assertEquals(beforeReplay, ledger(repository, owner.getAccountId()).getTotalBalance());

        String withdrawOrder = "sui-live-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("0.1");
        String withdrawDigest = transactions.withdrawNative(tenantId, withdrawOrder, owner.getUserId(), owner,
                external.getAddress(), withdrawAmount);
        assertEquals(withdrawDigest, transactions.withdrawNative(
                tenantId, withdrawOrder, owner.getUserId(), owner,
                external.getAddress(), withdrawAmount));
        assertTrue(transactions.confirmWithdrawal(tenantId, withdrawOrder, "SUI", owner.getAccountId(),
                withdrawAmount.add(new BigDecimal("0.01"))));

        String collectionNo = "sui-live-collection-" + UUID.randomUUID();
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, "SUI", "SUI",
                owner.getAddress(), hot.getAddress(), new BigDecimal("0.2"),
                new BigDecimal("0.01"), null));
        String collectionDigest = transactions.collectNative(
                tenantId, collectionNo, owner, hot.getAddress(), BigDecimal.valueOf(COLLECTION_MIST));
        assertEquals(collectionDigest, transactions.collectNative(
                tenantId, collectionNo, owner, hot.getAddress(), BigDecimal.valueOf(COLLECTION_MIST)));
        assertTrue(transactions.confirmCollection(tenantId, collectionNo));
        assertEquals(0, rpc.balance(hot.getAddress(), SuiRpcClient.SUI_COIN_TYPE)
                .compareTo(BigDecimal.valueOf(COLLECTION_MIST)));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='SUI'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("SUI_OWNER=" + owner.getAddress());
        System.out.println("SUI_EXTERNAL=" + external.getAddress());
        System.out.println("SUI_HOT=" + hot.getAddress());
        System.out.println("SUI_FAUCET_TX=" + faucetDigest);
        System.out.println("SUI_WITHDRAW_TX=" + withdrawDigest);
        System.out.println("SUI_COLLECTION_TX=" + collectionDigest);
    }

    private static String fund(String address) {
        String faucetUrl = env("SUI_FAUCET_URL", "https://faucet.testnet.sui.io/v2/gas");
        String body = "{\"FixedAmountRequest\":{\"recipient\":\"" + SuiHex.normalizeAddress(address) + "\"}}";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(faucetUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Sui faucet HTTP " + response.statusCode() + ": " + response.body());
                }
                return new ObjectMapper().readTree(response.body())
                        .path("coins_sent").path(0).path("transferTxDigest").asText();
            }
            throw new IllegalStateException("Sui faucet retry exhausted");
        } catch (IOException e) {
            return fundWithCurl(address, faucetUrl, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui faucet interrupted", e);
        }
    }

    private static String fundWithCurl(String address, String faucetUrl, String body) {
        try {
            Process process = new ProcessBuilder("curl", "-sS", "-X", "POST", faucetUrl,
                    "-H", "content-type: application/json", "-d", body)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Sui faucet curl failed for " + address + ": " + output);
            }
            return new ObjectMapper().readTree(output)
                    .path("coins_sent").path(0).path("transferTxDigest").asText();
        } catch (IOException e) {
            throw new IllegalStateException("Sui faucet curl IO failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui faucet curl interrupted", e);
        }
    }

    private static void waitForBalanceAtLeast(SuiRpcClient rpc, String address, long amount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.balance(address, SuiRpcClient.SUI_COIN_TYPE).compareTo(BigDecimal.valueOf(amount)) >= 0) {
                return;
            }
            sleep(1_000L);
        }
        throw new IllegalStateException("Sui faucet balance did not arrive for " + address);
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String accountId) {
        return repository.findLedgerBalance("SUI", "SUI", accountId).orElseThrow();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui live wait interrupted", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

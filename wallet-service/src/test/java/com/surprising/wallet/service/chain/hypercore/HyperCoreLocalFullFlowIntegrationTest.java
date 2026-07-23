package com.surprising.wallet.service.chain.hypercore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.ethereum.crypto.EthECKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.spongycastle.util.encoders.Hex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperCoreLocalFullFlowIntegrationTest {
    private static final String CHAIN = "HYPERCORE";
    private static final String USDC = "USDC";
    private static final String HYPE = "HYPE";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldExecuteUsdcAndHip1TenantFlowsWithoutDuplicateCreditsOrNonces() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("hypercore.local.flow.enabled"),
                "set -Dhypercore.local.flow.enabled=true to run the local HyperCore flow");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                required("HYPERCORE_DB_URL"),
                required("HYPERCORE_DB_USER"),
                System.getenv().getOrDefault("HYPERCORE_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository chainRepository = new ChainJdbcRepository(jdbc);
        HyperCoreRepository hyperCoreRepository = new HyperCoreRepository(jdbc, chainRepository);
        UUID tenantId = ensureTenant(jdbc);
        AccountChainProfile profile = chainRepository.findProfileByChain(CHAIN).orElseThrow();
        AccountSecp256k1KeyService keys = new AccountSecp256k1KeyService(
                new WalletKeyMaterialProvider(new WalletKeyConfigStore(jdbc),
                        WalletKeyMaterialProvider.Mode.WALLET_SERVER));

        try (FakeHyperCoreApi api = new FakeHyperCoreApi()) {
            HyperCoreApiClient client = new HyperCoreApiClient(JSON, api.baseUrl());
            HyperCoreDepositScanner scanner = new HyperCoreDepositScanner(
                    client, hyperCoreRepository, chainRepository);
            HyperCoreTransactionService transactions = new HyperCoreTransactionService(
                    client, new HyperCoreSigner(), hyperCoreRepository, chainRepository, keys);

            ChainAddressRecord userUsdc = address(keys, profile, tenantId, USDC, 100_001L, "DEPOSIT");
            ChainAddressRecord userHype = address(keys, profile, tenantId, HYPE, 100_001L, "DEPOSIT");
            ChainAddressRecord hotUsdc = address(keys, profile, tenantId, USDC, 0L, "DEPOSIT");
            ChainAddressRecord hotHype = address(keys, profile, tenantId, HYPE, 0L, "DEPOSIT");
            ChainAddressRecord external = address(keys, profile, tenantId, HYPE, 100_002L, "EXTERNAL");
            for (ChainAddressRecord value : List.of(userUsdc, userHype, hotUsdc, hotHype)) {
                assertEquals(1, chainRepository.upsertChainAddress(value));
            }
            UUID custodyAddressId = attachCustodyAddress(jdbc, tenantId, userUsdc);

            api.setBalance(userUsdc.getAddress(), USDC, new BigDecimal("100"));
            api.setBalance(userUsdc.getAddress(), HYPE, new BigDecimal("100"));
            api.setBalance(hotUsdc.getAddress(), USDC, BigDecimal.ZERO);
            api.setBalance(hotHype.getAddress(), HYPE, BigDecimal.ZERO);
            api.setBalance(external.getAddress(), USDC, BigDecimal.ZERO);
            api.setBalance(external.getAddress(), HYPE, BigDecimal.ZERO);

            scanner.scanAndCredit(profile);
            assertAmount(new BigDecimal("100"), ledger(chainRepository, USDC, userUsdc).getTotalBalance());
            assertAmount(new BigDecimal("100"), ledger(chainRepository, HYPE, userHype).getTotalBalance());
            scanner.scanAndCredit(profile);
            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from deposit_record where chain='HYPERCORE'", Integer.class));

            TokenDefinition hype = chainRepository.findToken(CHAIN, HYPE).orElseThrow();
            String runId = "HC-LOCAL-" + System.currentTimeMillis();
            withdraw(tenantId, chainRepository, transactions, api, profile,
                    runId + "-USDC-WD", userUsdc, external, null, new BigDecimal("20"));
            scanner.scanAndCredit(profile);
            assertAmount(new BigDecimal("80"), ledger(chainRepository, USDC, userUsdc).getTotalBalance());
            collect(tenantId, custodyAddressId, chainRepository, transactions, api, profile,
                    runId + "-USDC-COLLECT", userUsdc, hotUsdc, null, new BigDecimal("30"));
            scanner.scanAndCredit(profile);
            assertAmount(new BigDecimal("80"), ledger(chainRepository, USDC, userUsdc).getTotalBalance());
            assertCustodyBalance(api, userUsdc, hotUsdc, USDC, new BigDecimal("80"));

            withdraw(tenantId, chainRepository, transactions, api, profile,
                    runId + "-HYPE-WD", userHype, external, hype, new BigDecimal("20"));
            scanner.scanAndCredit(profile);
            assertAmount(new BigDecimal("80"), ledger(chainRepository, HYPE, userHype).getTotalBalance());
            collect(tenantId, custodyAddressId, chainRepository, transactions, api, profile,
                    runId + "-HYPE-COLLECT", userHype, hotHype, hype, new BigDecimal("30"));
            scanner.scanAndCredit(profile);
            assertAmount(new BigDecimal("80"), ledger(chainRepository, HYPE, userHype).getTotalBalance());
            assertCustodyBalance(api, userHype, hotHype, HYPE, new BigDecimal("80"));

            assertConcurrentNonceReservations(tenantId, dataSource, jdbc, chainRepository,
                    hyperCoreRepository, transactions, api, keys, profile, hype);
            assertConcurrentFirstSnapshotCreditsOnce(tenantId, dataSource, chainRepository,
                    hyperCoreRepository, keys, profile);

            assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance "
                    + "where available_balance < 0 or locked_balance < 0 or total_balance < 0", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance "
                    + "where chain='HYPERCORE' and locked_balance <> 0", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order "
                    + "where chain='HYPERCORE' and status <> 'CONFIRMED'", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from collection_record "
                    + "where chain='HYPERCORE' and status <> 'CONFIRMED'", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from hypercore_action_record "
                    + "where status <> 'ACCEPTED'", Integer.class));
            assertTrue(api.exchangeRequests() >= 4);
        }
    }

    private static void withdraw(UUID tenantId, ChainJdbcRepository repository,
                                 HyperCoreTransactionService transactions, FakeHyperCoreApi api,
                                 AccountChainProfile profile, String orderNo,
                                 ChainAddressRecord from, ChainAddressRecord to,
                                 TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? USDC : token.getSymbol();
        assertEquals(1, repository.createTenantWithdrawalOrder(
                tenantId, orderNo, from.getUserId(), CHAIN, symbol,
                from.getAddress(), from.getAccountId(), to.getAddress(), amount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(tenantId, CHAIN, symbol, from.getAccountId(), amount));
        assertEquals(1, repository.updateWithdrawalStatus(
                tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null));
        assertEquals(1, repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getAddress()));
        api.useSource(from.getAddress());
        String actionId = token == null
                ? transactions.sendUsd(profile, from, to.getAddress(), amount)
                : transactions.sendSpot(profile, from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(
                tenantId, CHAIN, orderNo, from.getAddress(), actionId));
        assertTrue(transactions.confirmWithdrawal(
                tenantId, orderNo, actionId, symbol, from.getAccountId(), amount));
    }

    private static void collect(UUID tenantId, UUID custodyAddressId,
                                ChainJdbcRepository repository,
                                HyperCoreTransactionService transactions, FakeHyperCoreApi api,
                                AccountChainProfile profile, String collectionNo,
                                ChainAddressRecord from, ChainAddressRecord hot,
                                TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? USDC : token.getSymbol();
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, CHAIN, symbol,
                from.getAddress(), hot.getAddress(), amount, BigDecimal.ZERO, null));
        assertEquals(1, repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null));
        api.useSource(from.getAddress());
        String actionId = token == null
                ? transactions.sendUsd(profile, from, hot.getAddress(), amount)
                : transactions.sendSpot(profile, from, token, hot.getAddress(), amount);
        assertEquals(1, repository.updateCollectionStatus(
                tenantId, CHAIN, collectionNo, "SENT", actionId, null, null));
        assertTrue(transactions.confirmCollection(tenantId, collectionNo, actionId));
    }

    private static void assertConcurrentNonceReservations(
            UUID tenantId, DriverManagerDataSource dataSource, JdbcTemplate jdbc,
            ChainJdbcRepository chainRepository, HyperCoreRepository hyperCoreRepository,
            HyperCoreTransactionService transactions, FakeHyperCoreApi api,
            AccountSecp256k1KeyService keys, AccountChainProfile profile, TokenDefinition hype) throws Exception {
        ChainAddressRecord source = address(keys, profile, tenantId, HYPE, 200_001L, "EXTERNAL");
        ChainAddressRecord destination = address(keys, profile, tenantId, HYPE, 200_002L, "EXTERNAL");
        api.setBalance(source.getAddress(), HYPE, new BigDecimal("100"));
        api.setBalance(destination.getAddress(), HYPE, BigDecimal.ZERO);
        api.useSource(source.getAddress());
        api.clearNonces();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                futures.add(workers.submit(() -> transaction.execute(status ->
                        transactions.sendSpot(profile, source, hype, destination.getAddress(),
                                new BigDecimal("0.01")))));
            }
            for (Future<String> future : futures) {
                assertTrue(future.get().startsWith("HC-spotSend-"));
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(32, api.nonces().size());
        assertEquals(32, Set.copyOf(api.nonces()).size());
        assertEquals(32, jdbc.queryForObject("select count(*) from hypercore_action_record "
                + "where from_address=?", Integer.class, source.getAddress()));
        assertFalse(hyperCoreRepository.tokenNameBySymbol(profile.getNetwork(), HYPE).isEmpty());
        assertFalse(chainRepository.findProfileByChain(CHAIN).isEmpty());
    }

    private static void assertConcurrentFirstSnapshotCreditsOnce(
            UUID tenantId, DriverManagerDataSource dataSource, ChainJdbcRepository chainRepository,
            HyperCoreRepository hyperCoreRepository, AccountSecp256k1KeyService keys,
            AccountChainProfile profile) throws Exception {
        ChainAddressRecord address = address(keys, profile, tenantId, HYPE, 300_001L, "DEPOSIT");
        chainRepository.upsertChainAddress(address);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                futures.add(workers.submit(() -> Boolean.TRUE.equals(transaction.execute(status ->
                        hyperCoreRepository.recordObservedBalance(
                                address, HYPE, new BigDecimal("25"), "{\"concurrent\":true}").isPresent()))));
            }
            int credited = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    credited++;
                }
            }
            assertEquals(1, credited);
        } finally {
            workers.shutdownNow();
        }
        assertAmount(new BigDecimal("25"), ledger(chainRepository, HYPE, address).getTotalBalance());
    }

    private static ChainAddressRecord address(AccountSecp256k1KeyService keys, AccountChainProfile profile,
                                              UUID tenantId, String symbol, long userId, String role) {
        ChainAddressRecord record = ChainAddressRecord.builder()
                .tenantId(tenantId)
                .chain(CHAIN)
                .assetSymbol(symbol)
                .accountId("pending")
                .userId(userId)
                .biz(userId == 0L ? 0 : 1)
                .addressIndex(0L)
                .walletRole(role)
                .enabled(true)
                .build();
        String value = "0x" + Hex.toHexString(
                EthECKey.fromPublicOnly(keys.key(profile, record).getPubKey()).getAddress());
        record.setAddress(value);
        record.setOwnerAddress(value);
        record.setAccountId(value);
        record.setDerivationPath("m/44/60/" + record.getBiz() + "/" + userId + "/0");
        return record;
    }

    private static UUID ensureTenant(JdbcTemplate jdbc) {
        UUID tenantId = UUID.nameUUIDFromBytes("hypercore-local-tenant".getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, 'hypercore-local', 'HyperCore local tenant', 2914)
                on conflict (id) do nothing
                """, tenantId);
        return tenantId;
    }

    private static UUID attachCustodyAddress(JdbcTemplate jdbc, UUID tenantId,
                                             ChainAddressRecord address) {
        Long chainAddressId = jdbc.queryForObject("""
                select id from chain_address
                 where tenant_id=? and chain='HYPERCORE' and asset_symbol='USDC'
                   and account_id=? and wallet_role='DEPOSIT'
                """, Long.class, tenantId, address.getAccountId());
        UUID id = UUID.nameUUIDFromBytes(
                ("hypercore-custody-" + address.getAddress()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, address_version, source, status,
                    derivation_subject, derivation_child)
                values (?, ?, ?, 'HYPERCORE', 'testnet', ?, 'hypercore-user', 0,
                        'API', 'ACTIVE', 100001, 0)
                on conflict (chain_address_id) do nothing
                """, id, tenantId, chainAddressId, address.getAddress());
        return id;
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol,
                                               ChainAddressRecord address) {
        return repository.findLedgerBalance(CHAIN, symbol, address.getAccountId()).orElseThrow();
    }

    private static void assertCustodyBalance(FakeHyperCoreApi api,
                                             ChainAddressRecord user, ChainAddressRecord hot,
                                             String symbol, BigDecimal expected) {
        assertAmount(expected, api.balance(user.getAddress(), symbol).add(api.balance(hot.getAddress(), symbol)));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), () -> "expected=" + expected + " actual=" + actual);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return value;
    }

    private static final class FakeHyperCoreApi implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Map<String, Map<String, BigDecimal>> balances = new ConcurrentHashMap<>();
        private final List<Long> nonces = new CopyOnWriteArrayList<>();
        private volatile String sourceAddress;
        private volatile int exchangeRequests;

        private FakeHyperCoreApi() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/info", this::info);
            server.createContext("/exchange", this::exchange);
            server.setExecutor(executor);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void useSource(String address) {
            sourceAddress = normalize(address);
        }

        synchronized void setBalance(String address, String symbol, BigDecimal amount) {
            balances.computeIfAbsent(normalize(address), ignored -> new ConcurrentHashMap<>())
                    .put(symbol, amount);
        }

        synchronized BigDecimal balance(String address, String symbol) {
            return balances.getOrDefault(normalize(address), Map.of())
                    .getOrDefault(symbol, BigDecimal.ZERO);
        }

        List<Long> nonces() {
            return List.copyOf(nonces);
        }

        void clearNonces() {
            nonces.clear();
        }

        int exchangeRequests() {
            return exchangeRequests;
        }

        private void info(HttpExchange exchange) throws IOException {
            JsonNode request = request(exchange);
            if ("spotMeta".equals(request.path("type").asText())) {
                respond(exchange, 200, """
                        {"tokens":[
                          {"name":"USDC","index":0,"tokenId":"0xeb62eee3685fc4c43992febcd9e75443",
                           "szDecimals":8,"weiDecimals":8,"isCanonical":true},
                          {"name":"HYPE","index":1105,"tokenId":"0x7317beb7cceed72ef0b346074cc8e7ab",
                           "szDecimals":2,"weiDecimals":8,"isCanonical":false}
                        ],"universe":[{"name":"HYPE/USDC","index":1105,"tokens":[1105,0],
                                       "isCanonical":false}]}
                        """);
                return;
            }
            String address = normalize(request.path("user").asText());
            StringBuilder body = new StringBuilder("{\"balances\":[");
            boolean first = true;
            for (Map.Entry<String, BigDecimal> entry : balances.getOrDefault(address, Map.of()).entrySet()) {
                if (!first) {
                    body.append(',');
                }
                first = false;
                body.append("{\"coin\":\"").append(entry.getKey()).append("\",\"total\":\"")
                        .append(entry.getValue().toPlainString()).append("\"}");
            }
            body.append("]}");
            respond(exchange, 200, body.toString());
        }

        private void exchange(HttpExchange exchange) throws IOException {
            JsonNode request = request(exchange);
            JsonNode action = request.path("action");
            JsonNode signature = request.path("signature");
            if (sourceAddress == null
                    || !signature.hasNonNull("r") || !signature.hasNonNull("s") || !signature.hasNonNull("v")
                    || !HyperCoreSigner.SIGNATURE_CHAIN_ID.equals(action.path("signatureChainId").asText())
                    || !"Testnet".equals(action.path("hyperliquidChain").asText())) {
                respond(exchange, 400, "{\"status\":\"err\"}");
                return;
            }
            long nonce = request.path("nonce").asLong();
            if (nonce != action.path("time").asLong()) {
                respond(exchange, 400, "{\"status\":\"err\"}");
                return;
            }
            String symbol = "usdSend".equals(action.path("type").asText())
                    ? USDC : action.path("token").asText();
            transfer(sourceAddress, action.path("destination").asText(), symbol,
                    new BigDecimal(action.path("amount").asText()));
            nonces.add(nonce);
            exchangeRequests++;
            respond(exchange, 200, "{\"status\":\"ok\",\"response\":{\"type\":\"default\"}}");
        }

        private synchronized void transfer(String from, String to, String symbol, BigDecimal amount) {
            BigDecimal available = balance(from, symbol);
            if (available.compareTo(amount) < 0) {
                throw new IllegalStateException("insufficient fake HyperCore balance");
            }
            setBalance(from, symbol, available.subtract(amount));
            setBalance(to, symbol, balance(to, symbol).add(amount));
        }

        private static JsonNode request(HttpExchange exchange) throws IOException {
            return JSON.readTree(exchange.getRequestBody());
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        private static String normalize(String address) {
            return address == null ? "" : address.toLowerCase();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyMultiChainLoadIntegrationTest {
    private static final List<ChainAsset> CHAINS = List.of(
            new ChainAsset(ChainType.ETH, "ETH"),
            new ChainAsset(ChainType.TRON, "TRX"),
            new ChainAsset(ChainType.SOLANA, "SOL"),
            new ChainAsset(ChainType.SUI, "SUI"));
    private static final BigDecimal DEPOSIT_AMOUNT = new BigDecimal("10.000000");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("3.000000");

    @Test
    void shouldKeepFundsAndCallbacksCorrectUnderMultiChainLoad() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("custody.load.enabled"),
                "run scripts/regtest/run-multichain-load.sh");
        int users = Integer.getInteger("custody.load.users", 1_000);
        int concurrency = Integer.getInteger("custody.load.concurrency", 32);
        int webhookWorkers = Integer.getInteger("custody.load.webhookWorkers", 12);
        assertTrue(users >= 100);

        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CustodyRepository custody = new CustodyRepository(jdbc);
        CustodyTenantChainRepository tenantChains = new CustodyTenantChainRepository(jdbc);
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyDepositCreditObserver observer = new CustodyDepositCreditObserver(
                jdbc, objectMapper, custody, tenantChains);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("custodyDepositCreditObserver", observer));
        ChainJdbcRepository chains = new ChainJdbcRepository(
                jdbc, beans.getBeanProvider(DepositCreditObserver.class));

        Tenant tenant = createTenant(jdbc, tenantChains);
        List<UserAccount> accounts = createAccounts(jdbc, tenant, users);
        CustodyCryptoService crypto = crypto();
        try (CallbackServer callback = new CallbackServer()) {
            UUID endpointId = insertWebhook(jdbc, crypto, tenant.id(), callback.url());

            List<DepositAttempt> deposits = new ArrayList<>(users * 2);
            for (UserAccount account : accounts) {
                DepositEvent event = depositEvent(account);
                deposits.add(new DepositAttempt(account, event));
                deposits.add(new DepositAttempt(account, event));
            }
            AtomicInteger credited = new AtomicInteger();
            Instant depositStarted = Instant.now();
            runConcurrently(deposits.size(), concurrency, index -> {
                DepositAttempt attempt = deposits.get(index);
                if (chains.recordAndCreditDeposit(
                        attempt.event(), 0L, 1, attempt.account().accountId())) {
                    credited.incrementAndGet();
                }
            });
            Duration depositDuration = Duration.between(depositStarted, Instant.now());
            assertEquals(users, credited.get(), "duplicate deposit scans must credit exactly once per user");

            Instant withdrawalStarted = Instant.now();
            runConcurrently(users, concurrency,
                    index -> createAndConfirmWithdrawal(jdbc, chains, custody, tenant.id(), accounts.get(index)));
            Duration withdrawalDuration = Duration.between(withdrawalStarted, Instant.now());

            CustodyWithdrawalReconciliationJob reconciliation =
                    new CustodyWithdrawalReconciliationJob(custody, objectMapper);
            for (int pass = 0; pass < users / 100 + 5; pass++) {
                reconciliation.reconcile();
                if (count(jdbc, "select count(*) from custody_withdrawal where status <> 'CONFIRMED'") == 0) {
                    break;
                }
            }
            assertEquals(0, count(jdbc,
                    "select count(*) from custody_withdrawal where status <> 'CONFIRMED'"));

            CustodyWebhookService webhooks = new CustodyWebhookService(
                    custody, crypto, objectMapper, "dev");
            List<CustodyWebhookDispatcher> dispatchers = new ArrayList<>();
            for (int worker = 0; worker < webhookWorkers; worker++) {
                dispatchers.add(new CustodyWebhookDispatcher(
                        custody, crypto, webhooks, new CustodyWebhookRetryPolicy()));
            }
            Instant webhookStarted = Instant.now();
            drainDueWebhooks(jdbc, dispatchers, concurrency);
            int retries = count(jdbc, """
                    select count(*) from custody_webhook_delivery_attempt
                     where status = 'RETRY_SCHEDULED'
                    """);
            assertTrue(retries > 0, "the callback server must exercise automatic retries");
            BigDecimal minimumRetrySeconds = jdbc.queryForObject("""
                    select min(extract(epoch from (next_attempt_at - completed_at)))
                      from custody_webhook_delivery_attempt
                     where status = 'RETRY_SCHEDULED'
                    """, BigDecimal.class);
            assertTrue(minimumRetrySeconds.compareTo(new BigDecimal("29")) >= 0,
                    "first retry must preserve the 30-second exponential-backoff interval");
            jdbc.update("update custody_webhook_delivery set next_attempt_at = now() where status = 'RETRY'");
            drainDueWebhooks(jdbc, dispatchers, concurrency);
            Duration webhookDuration = Duration.between(webhookStarted, Instant.now());

            int expectedDeliveries = users * 2;
            assertEquals(expectedDeliveries, count(jdbc,
                    "select count(*) from custody_webhook_delivery where status = 'DELIVERED'"));
            assertEquals(0, count(jdbc,
                    "select count(*) from custody_webhook_delivery where status <> 'DELIVERED'"));
            assertEquals(expectedDeliveries + retries, count(jdbc,
                    "select count(*) from custody_webhook_delivery_attempt"));
            assertEquals(expectedDeliveries + retries, callback.requests());
            assertEquals(0, callback.invalidRequests());
            assertEquals(endpointId, jdbc.queryForObject("""
                    select endpoint_id from custody_webhook_delivery limit 1
                    """, UUID.class));

            assertMoney(jdbc, users);
            writeReport(users, concurrency, webhookWorkers, retries,
                    depositDuration, withdrawalDuration, webhookDuration);
        }
    }

    private static Tenant createTenant(JdbcTemplate jdbc, CustodyTenantChainRepository tenantChains) {
        UUID tenantId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        int namespace = jdbc.queryForObject(
                "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        String suffix = tenantId.toString().substring(0, 8);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Multi-chain load tenant', ?)
                """, tenantId, "load-" + suffix, namespace);
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, 'Load administrator', 'test-only-hash', 'TENANT_ADMIN', 'ACTIVE')
                """, adminId, tenantId, "load-" + suffix + "@example.test");
        for (ChainAsset chain : CHAINS) {
            assertTrue(jdbc.update("""
                    update chain_profile
                       set enabled = true, scan_enabled = true,
                           withdraw_enabled = true, transfer_enabled = true
                     where chain = ? and enabled = true
                    """, chain.chain().name()) > 0,
                    "load-test chain profile must already have one enabled network");
            jdbc.update("""
                    update chain_asset set active = true
                     where chain = ? and symbol = ?
                    """, chain.chain().name(), chain.symbol());
            tenantChains.setStatus(tenantId, chain.chain().name(), "ACTIVE", adminId);
        }
        return new Tenant(tenantId, namespace);
    }

    private static List<UserAccount> createAccounts(JdbcTemplate jdbc, Tenant tenant, int users) {
        List<UserAccount> accounts = new ArrayList<>(users);
        for (int index = 0; index < users; index++) {
            ChainAsset chain = CHAINS.get(index % CHAINS.size());
            int subject = 100_000 + index;
            String accountId = "load-" + chain.chain().name().toLowerCase() + "-" + index;
            String address = address(chain.chain(), index);
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        tenant_id, chain, asset_symbol, account_id, user_id, biz,
                        address_index, address, derivation_path, wallet_role, enabled)
                    values (?, ?, ?, ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, tenant.id(), chain.chain().name(), chain.symbol(), accountId,
                    Integer.toUnsignedLong(subject), tenant.namespace(), address,
                    "m/load/" + tenant.namespace() + "/" + subject + "/0");
            UUID custodyAddressId = UUID.randomUUID();
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        subject, source, status, derivation_subject, derivation_child)
                    values (?, ?, ?, ?, 'load', ?, ?, 'API', 'ACTIVE', ?, 0)
                    """, custodyAddressId, tenant.id(), chainAddressId, chain.chain().name(), address,
                    "user-" + index, subject);
            accounts.add(new UserAccount(
                    index, custodyAddressId, chain.chain(), chain.symbol(), accountId, address, subject));
        }
        return accounts;
    }

    private static UUID insertWebhook(JdbcTemplate jdbc, CustodyCryptoService crypto,
                                      UUID tenantId, String url) {
        UUID endpointId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_webhook_endpoint(
                    id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, 'Load callback', ?, ?, 'ACTIVE', now())
                """, endpointId, tenantId, url, crypto.encrypt("whsec_load_test"));
        return endpointId;
    }

    private static CustodyCryptoService crypto() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        CustodySecurityProperties properties = new CustodySecurityProperties();
        properties.setSecretMasterKey(Base64.getEncoder().encodeToString(key));
        CustodyCryptoService crypto = new CustodyCryptoService(properties);
        crypto.validateConfiguration();
        return crypto;
    }

    private static DepositEvent depositEvent(UserAccount account) {
        return new DepositEvent(
                account.chain(), account.symbol(), String.format("%064x", account.index() + 1L),
                address(account.chain(), account.index() + 10_000), account.address(), DEPOSIT_AMOUNT,
                1_000_000L + account.index(), "load-block-" + account.index(),
                12, null, "{\"load\":true}");
    }

    private static void createAndConfirmWithdrawal(JdbcTemplate jdbc, ChainJdbcRepository chains,
                                                   CustodyRepository custody, UUID tenantId,
                                                   UserAccount account) {
        String orderNo = "LOAD-" + account.chain().name() + "-" + account.index();
        int created = chains.createTenantWithdrawalOrder(
                tenantId, orderNo, Integer.toUnsignedLong(account.derivationSubject()),
                account.chain().name(), account.symbol(), account.address(), account.accountId(),
                address(account.chain(), account.index() + 20_000), WITHDRAW_AMOUNT, BigDecimal.ZERO);
        assertEquals(1, created);
        assertTrue(chains.freezeLedgerBalance(
                account.chain().name(), account.symbol(), account.accountId(), WITHDRAW_AMOUNT));
        chains.updateWithdrawalStatus(
                account.chain().name(), orderNo, "FROZEN", account.address(), null, null);
        custody.insertCustodyWithdrawal(
                UUID.randomUUID(), tenantId, account.custodyAddressId(), orderNo,
                "external-" + account.index(), "load-idempotency-" + account.index(),
                account.chain().name(), account.symbol(),
                address(account.chain(), account.index() + 20_000), WITHDRAW_AMOUNT, BigDecimal.ZERO,
                "FROZEN", "API_KEY", "load-test");
        assertTrue(chains.settleLockedDebit(
                account.chain().name(), account.symbol(), account.accountId(), WITHDRAW_AMOUNT));
        chains.updateWithdrawalStatus(
                account.chain().name(), orderNo, "CONFIRMED", account.address(),
                "load-withdraw-" + account.index(), null);
    }

    private static void drainDueWebhooks(JdbcTemplate jdbc,
                                         List<CustodyWebhookDispatcher> dispatchers,
                                         int concurrency) throws Exception {
        int rounds = 0;
        while (count(jdbc, """
                select count(*) from custody_webhook_delivery
                 where status in ('PENDING', 'RETRY') and next_attempt_at <= now()
                """) > 0) {
            runConcurrently(dispatchers.size(), Math.min(concurrency, dispatchers.size()),
                    index -> dispatchers.get(index).dispatch());
            rounds++;
            if (rounds > 100) {
                throw new IllegalStateException("webhook queue did not drain after 100 rounds");
            }
        }
    }

    private static void assertMoney(JdbcTemplate jdbc, int users) {
        BigDecimal expectedAvailable = new BigDecimal(users).multiply(
                DEPOSIT_AMOUNT.subtract(WITHDRAW_AMOUNT));
        assertEquals(0, expectedAvailable.compareTo(jdbc.queryForObject("""
                select coalesce(sum(available_balance), 0) from ledger_balance
                 where tenant_id is not null and chain in ('ETH', 'TRON', 'SOLANA', 'SUI')
                """, BigDecimal.class)));
        assertEquals(0, expectedAvailable.compareTo(jdbc.queryForObject("""
                select coalesce(sum(total_balance), 0) from ledger_balance
                 where tenant_id is not null and chain in ('ETH', 'TRON', 'SOLANA', 'SUI')
                """, BigDecimal.class)));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ledger_balance
                 where tenant_id is not null and (available_balance < 0 or locked_balance <> 0
                    or total_balance <> available_balance)
                """, Integer.class));
        assertEquals(users, count(jdbc,
                "select count(*) from deposit_record where credited = true"));
        assertEquals(users, count(jdbc,
                "select count(*) from withdrawal_order where status = 'CONFIRMED'"));
        assertEquals(users, count(jdbc,
                "select count(*) from custody_ledger_entry where direction = 'CREDIT'"));
        assertEquals(users, count(jdbc,
                "select count(*) from custody_ledger_entry where direction = 'DEBIT'"));
        BigDecimal credits = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from custody_ledger_entry where direction = 'CREDIT'
                """, BigDecimal.class);
        BigDecimal debits = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from custody_ledger_entry where direction = 'DEBIT'
                """, BigDecimal.class);
        assertEquals(0, expectedAvailable.compareTo(credits.subtract(debits)));
    }

    private static void writeReport(int users, int concurrency, int webhookWorkers, int retries,
                                    Duration deposits, Duration withdrawals, Duration webhooks) throws IOException {
        Path report = Path.of("target", "multi-chain-load-report.properties");
        Files.createDirectories(report.getParent());
        String content = "users=" + users + '\n'
                + "chains=" + CHAINS.size() + '\n'
                + "concurrency=" + concurrency + '\n'
                + "webhookWorkers=" + webhookWorkers + '\n'
                + "depositAttempts=" + users * 2 + '\n'
                + "creditedDeposits=" + users + '\n'
                + "confirmedWithdrawals=" + users + '\n'
                + "webhookDeliveries=" + users * 2 + '\n'
                + "webhookRetries=" + retries + '\n'
                + "depositDurationMs=" + deposits.toMillis() + '\n'
                + "withdrawalDurationMs=" + withdrawals.toMillis() + '\n'
                + "webhookDurationMs=" + webhooks.toMillis() + '\n'
                + "depositAttemptsPerSecond=" + rate(users * 2, deposits) + '\n'
                + "withdrawalsPerSecond=" + rate(users, withdrawals) + '\n'
                + "webhookRequestsPerSecond=" + rate(users * 2 + retries, webhooks) + '\n';
        Files.writeString(report, content);
    }

    private static long rate(int operations, Duration duration) {
        return Math.round(operations / Math.max(duration.toMillis() / 1_000.0d, 0.001d));
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String address(ChainType chain, int index) {
        return switch (chain) {
            case ETH -> String.format("0x%040x", index + 1L);
            case TRON -> "T" + String.format("%033d", index + 1L).replace('0', 'A');
            case SOLANA -> "So" + String.format("%042d", index + 1L).replace('0', 'A');
            case SUI -> String.format("0x%064x", index + 1L);
            default -> throw new IllegalArgumentException("unsupported load-test chain: " + chain);
        };
    }

    private static void runConcurrently(int operations, int concurrency, IntConsumer operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(operations);
        try {
            for (int index = 0; index < operations; index++) {
                int task = index;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(30, TimeUnit.SECONDS));
                    operation.accept(task);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private record ChainAsset(ChainType chain, String symbol) {
    }

    private record Tenant(UUID id, int namespace) {
    }

    private record UserAccount(int index, UUID custodyAddressId, ChainType chain,
                               String symbol, String accountId, String address,
                               int derivationSubject) {
    }

    private record DepositAttempt(UserAccount account, DepositEvent event) {
    }

    private static final class CallbackServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newFixedThreadPool(32);
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger invalidRequests = new AtomicInteger();

        private CallbackServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/custody", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/custody";
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String eventId = exchange.getRequestHeaders().getFirst("X-Custody-Event-Id");
            String signature = exchange.getRequestHeaders().getFirst("X-Custody-Signature");
            String eventType = exchange.getRequestHeaders().getFirst("X-Custody-Event-Type");
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (eventId == null || signature == null || !signature.startsWith("v1=")
                    || eventType == null || body.length == 0) {
                invalidRequests.incrementAndGet();
            }
            int attempt = attempts.computeIfAbsent(eventId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            boolean failFirst = Math.floorMod(eventId.hashCode(), 5) == 0 && attempt == 1;
            if (failFirst) {
                byte[] response = "retry".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(503, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        }

        private int requests() {
            return requests.get();
        }

        private int invalidRequests() {
            return invalidRequests.get();
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}

package com.surprising.wallet.custody;

import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.service.CustodyTenantQueryService;
import com.surprising.wallet.service.CustodyTenantService;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.CustodyAssetDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Query cost stays bounded while chain and account ownership remain distinct.
 */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyControlPlaneQueriesIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private CountingDataSource dataSource;

    private JdbcTemplate jdbc;

    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;

    private CustodyTenantQueryService queries;

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {
    }

    @BeforeEach
    void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        dataSource = new CountingDataSource(source);
        jdbc = new JdbcTemplate(dataSource);
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        context.registerBean(CustodyTenantQueryService.class, () -> CustodyTestSupport.tenantQueries(jdbc));
        context.registerBean(CustodyTenantService.class, () -> CustodyTestSupport.tenants(jdbc));
        context.registerBean(CustodyGasService.class, () -> CustodyTestSupport.gas(jdbc));
        context.registerBean(CustodyAssetDashboardService.class, () -> CustodyTestSupport.assets(jdbc));
        context.refresh();
        queries = context.getBean(CustodyTenantQueryService.class);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void tenantPageQueryCostDoesNotGrowWithEveryReturnedTenant() {
        var tenants = new CustodyTenantTableRepository(jdbc);
        for (int i = 0; i < 20; i++) tenants.insert(UUID.randomUUID(), "query-cost-" + i, "Query tenant " + i);
        dataSource.selects.set(0);
        var result = queries.list(CustodyTestSupport.platformAdmin(), "query-cost-", "ACTIVE", 50, 0);
        var page = result.items();
        assertEquals(20, page.size());
        assertEquals(20, result.total());
        for (var row : page) {
            assertEquals(0L, row.addressCount());
            assertEquals(0L, row.gasAccountCount());
            assertEquals(0L, row.userCount());
        }
        assertTrue(dataSource.selects.get() <= 6, "tenant page must batch its counts, actual SELECTs=" + dataSource.selects.get());
        assertTrue(dataSource.snapshots.get() > 0, "tenant page must use a repeatable-read transaction");
    }

    @Test
    void gasListBatchesItsOwnedAddressAndNativeBalanceReads() {
        Map<String, BigDecimal> expected = new LinkedHashMap<>();
        int index = 0;
        for (String chain : List.of("ETH", "BASE", "BNB", "HYPEREVM", "POLYGON", "ARBITRUM")) {
            BigDecimal amount = BigDecimal.valueOf(++index);
            address(chain, "shared-gas-account", amount, true);
            expected.put(chain, amount);
        }
        dataSource.selects.set(0);
        var accounts = context.getBean(CustodyGasService.class).listForTenant(TENANT);
        assertEquals(6, accounts.size());
        for (var account : accounts) {
            assertEquals(0, expected.get(account.chain()).compareTo(account.availableBalance()));
            assertEquals(0, account.lockedBalance().signum());
        }
        assertTrue(dataSource.selects.get() <= 6, "gas list must batch its owned joins, actual SELECTs=" + dataSource.selects.get());
        assertTrue(dataSource.snapshots.get() > 0, "gas list must use a repeatable-read transaction");
    }

    @Test
    void gasOnOneChainCannotHideAnotherChainsCustomerBalanceAfterThatChainCloses() {
        address("ETH", "shared-account", new BigDecimal("0.9"), true);
        address("BASE", "shared-account", new BigDecimal("7"), false);
        jdbc.update("update chain_profile set enabled=false where chain='BASE'");
        var rows = context.getBean(CustodyAssetDashboardService.class).observedBalancesForTenant(TENANT);
        assertEquals(1, rows.size());
        assertEquals("BASE", rows.getFirst().chain());
        assertEquals(0, new BigDecimal("7").compareTo(rows.getFirst().totalBalance()));
        assertEquals(1L, rows.getFirst().addressCount());
    }

    @Test
    void dashboardCannotMergeCaseDistinctLedgerAccountsWithTheGasAccount() {
        address("ETH", "GasAccount", new BigDecimal("0.9"), true);
        address("ETH", "gasaccount", new BigDecimal("7"), false);
        UUID administrator = UUID.randomUUID();
        new CustodyTenantUserRepository(jdbc).insertTenantAdmin(administrator, TENANT, "query-admin@example.test", "Query admin", "fixture-password-hash");
        jdbc.update("""
            insert into custody_tenant_chain(tenant_id, chain, status, opened_by, opened_at)
            values (?, 'ETH', 'ACTIVE', ?, now())
            on conflict (tenant_id, chain) do update set status='ACTIVE',
                opened_by=excluded.opened_by, opened_at=now(), closed_at=null
            """, TENANT, administrator);
        String network = jdbc.queryForObject("select network from custody_gas_account where tenant_id=? and chain='ETH'", String.class, TENANT);
        jdbc.update("update chain_profile set enabled=true where chain='ETH' and network=?", network);
        var row = context.getBean(CustodyAssetDashboardService.class).dashboard(CustodyTestSupport.tenantAdmin(TENANT)).assets().stream().filter(value -> "ETH".equals(value.chain()) && "ETH".equals(value.assetSymbol())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("7").compareTo(row.totalBalance()));
        assertEquals(1, row.addressCount());
    }

    @Test
    void tenantSearchIsLiteralAndPageJsonRetainsItsPublicFieldNames() throws Exception {
        UUID literal = UUID.randomUUID();
        var tenants = new CustodyTenantTableRepository(jdbc);
        tenants.insert(literal, "literal-one", "Literal_% tenant");
        tenants.insert(UUID.randomUUID(), "literal-two", "LiteralAA tenant");
        var page = queries.list(CustodyTestSupport.platformAdmin(), "Literal_%", "ACTIVE", 20, 0);
        assertEquals(1, page.total());
        assertEquals(literal, page.items().getFirst().id());
        var mapper = new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
        var item = mapper.readTree(mapper.writeValueAsString(page)).path("items").get(0);
        for (String field : List.of("derivation_namespace", "ip_allowlist_enabled", "display_currency", "created_at", "updated_at", "addressCount", "gasAccountCount", "userCount")) assertTrue(item.has(field), field);
        assertFalse(item.has("derivationNamespace"));
    }

    @Test
    void duplicateAdministratorRollsBackTenantCreationAndItsAudit() {
        var service = context.getBean(CustodyTenantService.class);
        var actor = CustodyTestSupport.platformAdmin();
        service.create(actor, new CustodyTenantService.CreateTenantCommand("owner-first", "First tenant", "owner@example.test", "Owner", "StrongPassword123!"), "127.0.0.1");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.create(actor, new CustodyTenantService.CreateTenantCommand("owner-second", "Second tenant", "owner@example.test", "Owner", "StrongPassword123!"), "127.0.0.1"));
        assertEquals(0, jdbc.queryForObject("select count(*) from custody_tenant where slug='owner-second'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_tenant_user where email='owner@example.test'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_audit_log where action='TENANT.CREATE' and actor_id=?", Integer.class, actor.actorId().toString()));
    }

    @Test
    void unfinishedIdempotencyReceiptIsReportedAsProcessingWithoutCreatingAnotherWithdrawal() {
        UUID addressId = address("ETH", "waiting-customer", new BigDecimal("7"), false);
        var properties = new com.surprising.wallet.model.CustodySecurityProperties();
        properties.setSecretMasterKey("22".repeat(32));
        var crypto = new com.surprising.wallet.service.CustodyCryptoService(properties);
        var execution = org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyWithdrawalExecutionService.class);
        var idempotency = new com.surprising.wallet.repository.CustodyIdempotencyRepository(jdbc);
        assertTrue(idempotency.begin(TENANT, "waiting-request", "WITHDRAWAL.CREATE", crypto.sha256(addressId + "\nETH\nETH\nrecipient\n1\nclient-ref"), java.time.Instant.now().plusSeconds(300)));
        var service = new com.surprising.wallet.service.CustodyWithdrawalService(execution, context.getBean(CustodyGasService.class), org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyTenantChainService.class), crypto, new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(), CustodyTestSupport.events(jdbc), new com.surprising.wallet.repository.CustodyAddressRepository(jdbc), new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc), new CustodyTenantTableRepository(jdbc), new com.surprising.wallet.repository.ChainAddressRepository(jdbc), new com.surprising.wallet.repository.CustodyReorgDeficitRepository(jdbc), idempotency, new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc), new com.surprising.wallet.repository.CustodyAuditRepository(jdbc));
        var failure = assertThrows(IllegalStateException.class, () -> service.create(CustodyTestSupport.tenantAdmin(TENANT), new com.surprising.wallet.service.CustodyWithdrawalService.CreateWithdrawalCommand(addressId, "ETH", "ETH", "recipient", "1", "client-ref", true), "API", "waiting-request", "127.0.0.1"));
        assertTrue(failure.getMessage().contains("still processing"), failure.getMessage());
        org.mockito.Mockito.verifyNoInteractions(execution);
        assertEquals(0, jdbc.queryForObject("select count(*) from custody_withdrawal where tenant_id=?", Integer.class, TENANT));
    }

    private UUID address(String chain, String account, BigDecimal balance, boolean gas) {
        var profile = jdbc.queryForMap("""
            select network, native_symbol from chain_profile where chain=?
             order by enabled desc, network limit 1
            """, chain);
        String symbol = (String) profile.get("native_symbol");
        int namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?", Integer.class, TENANT);
        int subject = jdbc.queryForObject("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        String address = account + "-" + chain;
        long chainAddress = jdbc.queryForObject("""
            insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                address_index, address, derivation_path, wallet_role, enabled)
            values (?, ?, ?, ?, ?, ?, 0, ?, 'query-test', 'DEPOSIT', true) returning id
            """, Long.class, TENANT, chain, symbol, account, Integer.toUnsignedLong(subject), namespace, address);
        UUID custodyAddress = UUID.randomUUID();
        jdbc.update("""
            insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                subject, source, derivation_subject, derivation_child)
            values (?, ?, ?, ?, ?, ?, ?, 'API', ?, 0)
            """, custodyAddress, TENANT, chainAddress, chain, profile.get("network"), address, address, subject);
        if (gas) {
            jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network,
                    native_symbol, low_balance_threshold) values (?, ?, ?, ?, ?, ?, 0.1)
                """, UUID.randomUUID(), TENANT, custodyAddress, chain, profile.get("network"), symbol);
        }
        jdbc.update("""
            insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                available_balance, locked_balance, total_balance) values (?, ?, ?, ?, ?, 0, ?)
            """, TENANT, chain, symbol, account, balance, balance);
        return custodyAddress;
    }

    /**
     * Count driver executions rather than repository methods, so batching is observable.
     */
    private static final class CountingDataSource extends DelegatingDataSource {

        private final AtomicInteger selects = new AtomicInteger();

        private final AtomicInteger snapshots = new AtomicInteger();

        CountingDataSource(DataSource source) {
            super(source);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return connection(super.getConnection(user, password));
        }

        private Connection connection(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                if (method.getName().equals("setTransactionIsolation") && args != null && Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(args[0])) {
                    snapshots.incrementAndGet();
                }
                Object result = invoke(target, method, args);
                if (result instanceof Statement statement) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                    Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, values) -> {
                        String executing = sql;
                        if (executing == null) {
                            if (values != null && values.length > 0 && values[0] instanceof String text) {
                                executing = text;
                            } else
                                executing = "";
                        }
                        if (m.getName().startsWith("execute") && executing.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                            selects.incrementAndGet();
                        }
                        return invoke(statement, m, values);
                    });
                }
                return result;
            });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}

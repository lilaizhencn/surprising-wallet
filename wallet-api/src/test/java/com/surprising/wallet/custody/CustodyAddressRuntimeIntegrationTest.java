package com.surprising.wallet.custody;

import com.surprising.wallet.chain.WalletKeyTestFixture;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.AddressRuntimeTestSupport;
import com.surprising.wallet.testutil.DepositTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyAddressRuntimeIntegrationTest {
    private JdbcTemplate jdbc;
    private AddressRuntimeTestSupport engines;
    private CustodyAddressService service;
    private Evm7702AccountRepository projections;
    private AnnotationConfigApplicationContext context;
    private UUID tenant;
    private final List<String> selects = new ArrayList<>();

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = spy(new JdbcTemplate(ds));
        doAnswer(call -> {
            selects.add(((String) call.getArgument(0)).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
            return call.callRealMethod();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        tenant = createTenant();
        engines = new AddressRuntimeTestSupport(DepositTestSupport.repository(jdbc), WalletKeyTestFixture.provider());
        projections = mock(Evm7702AccountRepository.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(CustodyAddressService.class, () -> new CustodyAddressService(
                new CustodyTenantTableRepository(jdbc), new ChainAddressRepository(jdbc), engines.runtime,
                mock(CustodyTenantChainService.class), new CustodyJacksonConfiguration().custodyObjectMapper(), projections,
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyDerivationSubjectRepository(jdbc), new CustodyAuditRepository(jdbc), new LedgerBalanceRepository(jdbc)));
        context.refresh();
        service = context.getBean(CustodyAddressService.class);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"ETH", "APTOS", "SUI", "BTC", "BCH", "LTC", "DOGE"})
    void realCreationReadsOneProfileAndOnePersistedChainAddressAndReplaysWithoutDerivation(String chain) {
        var profile = enableProfile(chain);
        selects.clear();
        var created = create(tenant, chain, "customer");
        assertEquals(1, countSelects("chain_profile"));
        assertEquals(1, countSelects("chain_address"));
        var row = new CustodyAddressRepository(jdbc).require(tenant, created.id());
        var chainRow = new ChainAddressRepository(jdbc).findOwned(tenant, row.chainAddressId()).orElseThrow();
        assertEquals(profile.getNetwork(), row.network());
        assertEquals(created.address(), chainRow.getAddress());
        assertEquals(tenant, chainRow.getTenantId());
        assertEquals(1L, chainRow.getAddressIndex());
        assertEquals(engines.derivation.deriveAddress(profile, chainRow.getUserId(), chainRow.getBiz(), 1L, "DEPOSIT").getAddress(), created.address());
        assertTrue(engines.runtime.checkAddress(chain, created.address()));
        selects.clear();
        assertEquals(created.id(), create(tenant, chain, "customer").id());
        assertEquals(0, countSelects("chain_profile"));
        assertEquals(0, countSelects("chain_address"));
    }

    @Test
    void projectionFailureRollsBackTheRealDerivedAddressTenantBindingAndAudit() {
        enableProfile("ETH");
        doThrow(new IllegalStateException("projection failure")).when(projections)
                .createProjection(eq(tenant), any(UUID.class), eq("ETH"), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> create(tenant, "ETH", "rollback"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from chain_address where tenant_id=?", Long.class, tenant));
        assertEquals(0L, jdbc.queryForObject("select count(*) from custody_address where tenant_id=?", Long.class, tenant));
        assertEquals(0L, jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=?", Long.class, tenant));
    }

    @Test
    void typedCollectionRowsPreservePrecisionPendingSuppressionTenantScopesAndOrdering() {
        enableProfile("ETH");
        UUID other = createTenant();
        var a = owner(tenant, create(tenant, "ETH", "a").id());
        var b = owner(tenant, create(tenant, "ETH", "b").id());
        var cView = create(other, "ETH", "c");
        var c = owner(other, cView.id());
        deposit(a, new BigDecimal("10.123456789012345678"));
        deposit(a, new BigDecimal("2.000000000000000001"));
        deposit(b, new BigDecimal("20"));
        BigDecimal large = new BigDecimal(BigInteger.ONE.shiftLeft(180)).add(new BigDecimal("0.000000000000000001"));
        deposit(c, large);
        collection(a, new BigDecimal("3.000000000000000002"), "CONFIRMED");
        collection(a, new BigDecimal("100"), "FAILED");
        collection(b, BigDecimal.ONE, "SENT");
        var planning = new CollectionPlanningService(new DepositRecordRepository(jdbc), new CollectionRecordRepository(jdbc),
                new ChainAssetRepository(jdbc), new CustodyAddressRepository(jdbc), new ChainAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc));
        var candidates = planning.candidates("ETH", BigDecimal.ONE, 20);
        assertEquals(List.of(c.getAddress(), a.getAddress()), candidates.stream().map(row -> row.getAddress()).toList());
        assertEquals(0, large.compareTo(candidates.getFirst().getAmount()));
        assertEquals(0, new BigDecimal("9.123456789012345677").compareTo(candidates.getLast().getAmount()));
        assertEquals(other, candidates.getFirst().getTenantId());
        var custody = new CustodyAddressRepository(jdbc);
        assertEquals(cView.id(), custody.listByTenantAndAddress(other, c.getAddress(), "ETH").getFirst().id());
        assertTrue(custody.listByTenantAndAddress(tenant, c.getAddress(), "ETH").isEmpty());
        var ledger = new LedgerBalanceRepository(jdbc);
        ledger.increment(tenant, "ETH", "ETH", a.getAccountId(), new BigDecimal("5.000000000000000001"));
        assertEquals(a.getAccountId(), ledger.findAvailableAccount(tenant, "ETH", "ETH", a.getAccountId(), new BigDecimal("5.000000000000000001")).orElseThrow());
        assertTrue(ledger.findAvailableAccount(other, "ETH", "ETH", a.getAccountId(), BigDecimal.ONE).isEmpty());
        assertTrue(ledger.findAvailableAccount(tenant, "ETH", "ETH", a.getAccountId(), new BigDecimal("5.000000000000000002")).isEmpty());
    }

    private AccountChainProfile enableProfile(String chain) {
        var profile = new ChainProfileRepository(jdbc).listAll().stream().filter(row -> chain.equals(row.getChain()))
                .filter(row -> row.getNetwork().startsWith("testnet") || "sepolia".equals(row.getNetwork())).findFirst().orElseThrow();
        jdbc.update("update chain_profile set enabled=false where chain=?", chain);
        jdbc.update("update chain_profile set enabled=true where id=?", profile.getId());
        profile.setEnabled(true);
        return profile;
    }

    private UUID createTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'Real address runtime test')", id, "runtime-" + id);
        return id;
    }

    private CustodyAddressService.AddressView create(UUID owner, String chain, String subject) {
        var actor = new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(), owner, "test", "API_KEY", Set.of("addresses:write"));
        return service.create(actor, new CustodyAddressService.CreateAddressCommand(chain, subject, 1L, null, Map.of()), "API", "127.0.0.1");
    }

    private ChainAddressRecord owner(UUID owner, UUID id) {
        return new ChainAddressRepository(jdbc).findOwned(owner, new CustodyAddressRepository(jdbc).require(owner, id).chainAddressId()).orElseThrow();
    }

    private void deposit(ChainAddressRecord owner, BigDecimal amount) {
        var repo = new DepositRecordRepository(jdbc);
        String hash = UUID.randomUUID().toString();
        repo.upsert(owner.getTenantId(), "ETH", "ETH", hash, 0, "external", owner.getAddress(), null, amount, 1L, hash, 12, "CONFIRMED", owner.getAccountId(), "{}");
        assertTrue(repo.markCredited(owner.getTenantId(), "ETH", hash, 0).isPresent());
    }

    private void collection(ChainAddressRecord owner, BigDecimal amount, String state) {
        var repo = new CollectionRecordRepository(jdbc);
        UUID custody = new CustodyAddressRepository(jdbc).listByTenantAndAddress(owner.getTenantId(), owner.getAddress(), "ETH").getFirst().id();
        String no = UUID.randomUUID().toString();
        repo.create(owner.getTenantId(), custody, no, "ETH", "ETH", owner.getAddress(), "target", amount, BigDecimal.ZERO, "{}");
        repo.updateStatus(owner.getTenantId(), "ETH", no, state, "hash-" + no, null, null);
    }

    private long countSelects(String table) { return selects.stream().filter(sql -> sql.contains(" from " + table + " ")).count(); }
}

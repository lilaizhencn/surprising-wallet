package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyAddressService;
import com.surprising.wallet.service.CustodyTenantChainService;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real application transactions and subject row locks; chain derivation and EVM projection are controlled dependencies. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyAddressAllocationIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private UUID tenant;
    private CustodyPrincipal principal;
    private CustodyAddressService service;
    private Evm7702AccountRepository projections;
    private BlockchainRuntimeService runtime;

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        tenant = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'Address allocation test')", tenant, "allocation-" + tenant);
        principal = new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(), tenant,
                "allocation-test", "API_KEY", Set.of("addresses:read", "addresses:write"));
        var chainAddresses = new ChainAddressRepository(jdbc);
        runtime = mock(BlockchainRuntimeService.class);
        when(runtime.requireRuntime("ETH")).thenReturn(new BlockchainRuntimeService.RuntimeChain(
                ChainType.ETH, "ETH", "devtest", "evm", "ETH", 1));
        when(runtime.generateDepositAddressAtIndex(eq("ETH"), anyLong(), anyInt(), anyLong())).thenAnswer(call -> {
            long user = call.getArgument(1), child = call.getArgument(3);
            int biz = call.getArgument(2);
            String address = String.format("0x%040x", user * 100_000L + child);
            chainAddresses.upsert(ChainAddressRecord.builder().chain("ETH").assetSymbol("ETH")
                    .accountId(address).userId(user).biz(biz).addressIndex(child).address(address)
                    .ownerAddress(address).derivationPath("test-path").walletRole("DEPOSIT").enabled(true).build());
            return Address.builder().address(address).index(Math.toIntExact(child)).build();
        });
        projections = mock(Evm7702AccountRepository.class);
        var chains = mock(CustodyTenantChainService.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(CustodyAddressService.class, () -> new CustodyAddressService(
                new CustodyTenantTableRepository(jdbc), chainAddresses, runtime, chains,
                new CustodyJacksonConfiguration().custodyObjectMapper(), projections,
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyDerivationSubjectRepository(jdbc), new CustodyAuditRepository(jdbc),
                new LedgerBalanceRepository(jdbc)));
        context.refresh();
        service = context.getBean(CustodyAddressService.class);
        assertTrue(AopUtils.isAopProxy(service));
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void concurrentSameSubjectAndVersionAllocateOneAddressProjectionAndAudit() throws Exception {
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<Future<UUID>>();
            for (int index = 0; index < 24; index++) calls.add(pool.submit(() -> {
                start.await();
                return service.create(principal, command(), "API", "127.0.0.1").id();
            }));
            start.countDown();
            var ids = new HashSet<UUID>();
            for (var call : calls) ids.add(call.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, rows("custody_address"));
        assertEquals(1, rows("chain_address"));
        assertEquals(1, rows("custody_derivation_subject"));
        assertEquals(1, rows("custody_audit_log"));
        verify(runtime, times(1)).generateDepositAddressAtIndex(eq("ETH"), anyLong(), anyInt(), eq(2L));
        verify(projections, times(1)).createProjection(eq(tenant), any(), eq("ETH"), eq("devtest"), anyString());
    }

    @Test void projectionFailureRollsBackAllocationAndAllowsACleanRetry() {
        doThrow(new IllegalStateException("projection unavailable")).when(projections)
                .createProjection(eq(tenant), any(), eq("ETH"), eq("devtest"), anyString());
        assertThrows(IllegalStateException.class, () -> service.create(principal, command(), "API", "127.0.0.1"));
        assertNoAllocation();
        reset(projections);
        var created = service.create(principal, command(), "API", "127.0.0.1");
        assertNotNull(created.id());
        assertEquals(2, created.addressVersion());
        assertEquals(1, rows("custody_address"));
        assertEquals(1, rows("chain_address"));
        assertEquals(1, rows("custody_derivation_subject"));
        assertEquals(1, rows("custody_audit_log"));
    }

    @Test void auditFailureRollsBackCreationAndMetadataUpdate() {
        assertThrows(DataAccessException.class, () -> service.create(principal, command(), "API", "invalid-ip"));
        assertNoAllocation();
        var created = service.create(principal, command(), "API", "127.0.0.1");
        assertThrows(DataAccessException.class, () -> service.update(principal, created.id(),
                new CustodyAddressService.UpdateAddressCommand("changed", Map.of("changed", true), "DISABLED"), "invalid-ip"));
        var unchanged = new CustodyAddressRepository(jdbc).require(tenant, created.id());
        assertEquals("original", unchanged.label());
        assertEquals("ACTIVE", unchanged.status());
        assertEquals("{}", unchanged.metadataJson());
        assertEquals(1, rows("custody_audit_log"));
    }

    private void assertNoAllocation() {
        assertEquals(0, rows("custody_address"));
        assertEquals(0, rows("chain_address"));
        assertEquals(0, rows("custody_derivation_subject"));
        assertEquals(0, rows("custody_audit_log"));
    }
    private long rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenant);
    }
    private static CustodyAddressService.CreateAddressCommand command() {
        return new CustodyAddressService.CreateAddressCommand("ETH", "same-subject", 2L, "original", Map.of());
    }
}

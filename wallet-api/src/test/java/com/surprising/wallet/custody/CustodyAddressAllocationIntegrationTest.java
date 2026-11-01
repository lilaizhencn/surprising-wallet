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
    private com.surprising.wallet.testutil.MpcSigningTestSupport mpc;
    private com.surprising.wallet.common.chain.AccountChainProfile profile;

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
        mpc = new com.surprising.wallet.testutil.MpcSigningTestSupport(jdbc);
        profile = com.surprising.wallet.common.chain.AccountChainProfile.builder()
                .chain("ETH").network("devtest").family("evm").nativeSymbol("ETH").enabled(true).build();
        var profiles = mock(ChainProfileRepository.class);
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(profile));
        projections = mock(Evm7702AccountRepository.class);
        var chains = mock(CustodyTenantChainService.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> mpc.manager);
        context.registerBean(CustodyAddressService.class, () -> new CustodyAddressService(
                new CustodyTenantTableRepository(jdbc), chainAddresses, profiles, chains,
                new CustodyJacksonConfiguration().custodyObjectMapper(), projections,
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyDerivationSubjectRepository(jdbc), new CustodyAuditRepository(jdbc),
                new LedgerBalanceRepository(jdbc), mpc.signing, mpc.nodes,
                com.surprising.wallet.testutil.CustodyTestSupport.cardanoAnchors(jdbc),com.surprising.wallet.testutil.CustodyTestSupport.moneroAnchors(jdbc), mpc.manager));
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
        assertEquals(1, rows("wallet_mpc_key"));
        assertEquals(1, rows("wallet_mpc_key_epoch"));
        clearInvocations(mpc.signing, mpc.nodes);
        service.create(principal, command(), "API", "127.0.0.1");
        verifyNoInteractions(mpc.signing, mpc.nodes);
        verify(projections, times(1)).createProjection(eq(tenant), any(), eq("ETH"), eq("devtest"), anyString());
    }

    @Test void projectionFailureRollsBackPublicationAndReusesCommittedKeyOnRetry() {
        doThrow(new IllegalStateException("projection unavailable")).when(projections)
                .createProjection(eq(tenant), any(), eq("ETH"), eq("devtest"), anyString());
        assertThrows(IllegalStateException.class, () -> service.create(principal, command(), "API", "127.0.0.1"));
        assertNoPublication();
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
        assertNoPublication();
        var created = service.create(principal, command(), "API", "127.0.0.1");
        assertThrows(DataAccessException.class, () -> service.update(principal, created.id(),
                new CustodyAddressService.UpdateAddressCommand("changed", Map.of("changed", true), "DISABLED"), "invalid-ip"));
        var unchanged = new CustodyAddressRepository(jdbc).require(tenant, created.id());
        assertEquals("original", unchanged.label());
        assertEquals("ACTIVE", unchanged.status());
        assertEquals("{}", unchanged.metadataJson());
        assertEquals(1, rows("custody_audit_log"));
    }

    @Test void unavailableDkgRetainsCoordinatesWithoutPublishingAnAddress() {
        doAnswer(call -> { throw new com.surprising.wallet.model.mpc.MpcPendingException(call.getArgument(1), "CREATING"); })
                .when(mpc.signing).advanceKey(any(), any(), anyList());
        var pending = assertThrows(com.surprising.wallet.model.mpc.MpcPendingException.class,
                () -> service.create(principal, command(), "API", "127.0.0.1"));
        assertNoPublication();
        assertEquals(com.surprising.wallet.model.mpc.MpcKey.State.CREATING,
                mpc.keys.find(tenant, pending.reference()).orElseThrow().state());
    }

    @Test void creationRejectsAnOuterTransactionBeforeAllocatingOrCallingNodes() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(mpc.manager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> service.create(principal, command(), "API", "127.0.0.1")));
        assertEquals(0, rows("custody_derivation_subject"));
        assertEquals(0, rows("wallet_mpc_key"));
        assertEquals(0, rows("chain_address"));
        verifyNoInteractions(mpc.signing, mpc.nodes);
    }

    @Test void publishedAddressCannotBeReboundToAnotherKeyTenantOrAddress() {
        var view = service.create(principal, command(), "API", "127.0.0.1");
        long id = new CustodyAddressRepository(jdbc).require(tenant, view.id()).chainAddressId();
        assertNotNull(new ChainAddressRepository(jdbc).findOwned(tenant, id).orElseThrow().getMpcKeyId());
        assertThrows(DataAccessException.class, () -> jdbc.update("update chain_address set mpc_key_id=null where id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update chain_address set tenant_id=? where id=?", UUID.randomUUID(), id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update chain_address set address='other-address' where id=?", id));
        assertEquals(1, jdbc.update("update chain_address set enabled=false where id=?", id));
        assertFalse(new ChainAddressRepository(jdbc).findOwned(tenant, id).orElseThrow().getEnabled());
    }

    private void assertNoPublication() {
        assertEquals(0, rows("custody_address"));
        assertEquals(0, rows("chain_address"));
        assertEquals(1, rows("custody_derivation_subject"));
        assertEquals(1, rows("wallet_mpc_key"));
        assertEquals(1, rows("wallet_mpc_key_epoch"));
        assertEquals(0, rows("custody_audit_log"));
    }
    private long rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenant);
    }
    private static CustodyAddressService.CreateAddressCommand command() {
        return new CustodyAddressService.CreateAddressCommand("ETH", "same-subject", 2L, "original", Map.of());
    }
}

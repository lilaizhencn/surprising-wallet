package com.surprising.wallet.custody;

import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PG18 publication boundaries; test-only public anchor and local DKG substitutes. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CardanoAddressPublicationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private JdbcTemplate jdbc;
    private CustodyAddressService service;
    private CardanoScanAnchorService anchors;
    private CustodyPrincipal principal;
    private final CustodyAddressService.CreateAddressCommand command = new CustodyAddressService.CreateAddressCommand("ADA", "anchor-customer", 0L, null, Map.of());

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource(); CustodyIntegrationDatabase.reset(ds); jdbc = new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='ADA'");
        jdbc.update("update chain_profile set enabled=true where chain='ADA' and network='preprod'");
        jdbc.update("update chain_asset set active=true where chain='ADA' and symbol='ADA'");
        var mpc = new MpcSigningTestSupport(jdbc); anchors = CustodyTestSupport.cardanoAnchors(jdbc);
        service = new CustodyAddressService(new CustodyTenantTableRepository(jdbc), new ChainAddressRepository(jdbc),
                new ChainProfileRepository(jdbc), mock(CustodyTenantChainService.class), new ObjectMapper(), new Evm7702AccountRepository(jdbc),
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc),
                new CustodyAuditRepository(jdbc), new LedgerBalanceRepository(jdbc), mpc.signing, mpc.nodes, anchors,CustodyTestSupport.moneroAnchors(jdbc), mpc.manager);
        principal = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, UUID.randomUUID(), TENANT, "anchor-test", "TENANT_ADMIN", Set.of("*"));
    }

    @Test void newAddressAndCanonicalAnchorPublishTogetherAndRetryDoesNotCallRpc() {
        var first = service.create(principal, command, "API", "127.0.0.1");
        var state = new ChainScanHeightRepository(jdbc).nativeState("ADA", CardanoSubmissionService.SCANNER, false).orElseThrow();
        assertEquals("11".repeat(32), state.genesisHash()); assertEquals(100, state.safeHeight());
        assertEquals(200, new ChainScanBlockRepository(jdbc).nativePoint("ADA", CardanoSubmissionService.SCANNER, 100).orElseThrow().point().slot());
        assertEquals(first.id(), service.create(principal, command, "API", "127.0.0.1").id());
        verify(anchors, times(1)).prepare(any()); verify(anchors, times(1)).publish(any(), any());
    }

    @Test void failedAddressProjectionRollsBackAnchorAndPublicAddressButRetainsRecoverableDkg() {
        jdbc.execute("create function public.cardano_publish_reject() returns trigger language plpgsql as $$ begin raise exception 'reject publication';end $$;create trigger cardano_publish_reject before insert on custody_address for each row execute function public.cardano_publish_reject()");
        try { assertThrows(RuntimeException.class, () -> service.create(principal, command, "API", "127.0.0.1")); }
        finally { jdbc.execute("drop trigger cardano_publish_reject on custody_address;drop function public.cardano_publish_reject()"); }
        assertTrue(new ChainScanHeightRepository(jdbc).nativeState("ADA", CardanoSubmissionService.SCANNER, false).isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from chain_address where tenant_id=? and chain='ADA' and mpc_key_id is not null", Integer.class, TENANT));
        assertEquals(1, jdbc.queryForObject("select count(*) from wallet_mpc_key where tenant_id=? and key_group='ADA' and state='ACTIVE'", Integer.class, TENANT));
        var recovered = service.create(principal, command, "API", "127.0.0.1");
        assertNotNull(recovered.address());
        assertTrue(new ChainScanHeightRepository(jdbc).nativeState("ADA", CardanoSubmissionService.SCANNER, false).isPresent());
        assertEquals(1, jdbc.queryForObject("select count(*) from wallet_mpc_key where tenant_id=? and key_group='ADA'", Integer.class, TENANT));
    }
}

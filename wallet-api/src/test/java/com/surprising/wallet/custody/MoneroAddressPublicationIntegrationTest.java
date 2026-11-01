package com.surprising.wallet.custody;

import com.surprising.wallet.chain.monero.MoneroDaemonClient;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 publication/ownership boundaries with explicit public-chain and DKG substitutes. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class MoneroAddressPublicationIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private JdbcTemplate jdbc;
    private CustodyAddressService service;
    private MoneroDaemonClient daemon;
    private MpcSigningTestSupport mpc;
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=(network='regtest') where chain='XMR'");
        daemon=mock(MoneroDaemonClient.class);
        when(daemon.network("regtest")).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new MoneroDaemonClient.Network("regtest","11".repeat(32),110);});
        when(daemon.header("regtest",100)).thenReturn(new MoneroDaemonClient.Header(100,"22".repeat(32),"00".repeat(32)));
        mpc=new MpcSigningTestSupport(jdbc);
        var anchors=new MoneroScanAnchorService(daemon,new ChainScanHeightRepository(jdbc),new ChainScanBlockRepository(jdbc));
        service=new CustodyAddressService(new CustodyTenantTableRepository(jdbc),new ChainAddressRepository(jdbc),new ChainProfileRepository(jdbc),
                mock(CustodyTenantChainService.class),new ObjectMapper(),new Evm7702AccountRepository(jdbc),new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc),new CustodyDerivationSubjectRepository(jdbc),new CustodyAuditRepository(jdbc),new LedgerBalanceRepository(jdbc),
                mpc.signing,mpc.nodes,CustodyTestSupport.cardanoAnchors(jdbc),anchors,mpc.manager);
    }
    private CustodyPrincipal principal(UUID tenant) {return new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,UUID.randomUUID(),tenant,"native-monero","TENANT_ADMIN",Set.of("*"));}
    private CustodyAddressService.CreateAddressCommand command(String subject){return new CustodyAddressService.CreateAddressCommand("XMR",subject,0L,null,Map.of());}
    @Test void oneTenantRootAndIndependentChildAddressesPublishWithCanonicalAnchor() {
        var principal=principal(TENANT);var first=service.create(principal,command("alice"),"API","127.0.0.1");
        var second=service.create(principal,command("bob"),"API","127.0.0.1");assertNotEquals(first.address(),second.address());
        var decoded=com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec.decode(first.address());assertTrue(decoded.subaddress());
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_key where key_group='XMR_ROOT'",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_key where key_group='XMR'",Integer.class));
        var state=new ChainScanHeightRepository(jdbc).nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow();
        assertEquals(100,state.safeHeight());assertEquals("11".repeat(32),state.genesisHash());
        assertEquals(first.id(),service.create(principal,command("alice"),"API","127.0.0.1").id());
        verify(daemon,times(2)).network("regtest");
        var json=new ObjectMapper();var policies=jdbc.queryForList("select request->>'messageHex' from wallet_mpc_key_epoch",String.class);
        var root=jdbc.queryForObject("select id from wallet_mpc_key where key_group='XMR_ROOT'",UUID.class);
        for(String encoded:policies){var policy=json.readTree(java.util.HexFormat.of().parseHex(encoded));if(policy.path("kind").asText().equals("ADDRESS"))assertEquals(root.toString(),policy.path("rootKeyId").asText());}
    }
    @Test void addressProjectionFailureRollsBackAnchorButRetainsOriginalNativeRootAndChild() {
        jdbc.execute("create function public.monero_publish_reject() returns trigger language plpgsql as $$ begin raise exception 'reject publication';end $$;create trigger monero_publish_reject before insert on custody_address for each row execute function public.monero_publish_reject()");
        try{assertThrows(RuntimeException.class,()->service.create(principal(TENANT),command("alice"),"API","127.0.0.1"));}
        finally{jdbc.execute("drop trigger monero_publish_reject on custody_address;drop function public.monero_publish_reject()");}
        assertTrue(new ChainScanHeightRepository(jdbc).nativeState("XMR",MoneroScanAnchorService.SCANNER,false).isEmpty());
        assertEquals(0,jdbc.queryForObject("select count(*) from chain_address where chain='XMR'",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_key where algorithm='MONERO_NATIVE_2_OF_3' and state='ACTIVE'",Integer.class));
        var original=jdbc.queryForObject("select public_key_hex from wallet_mpc_key where key_group='XMR'",String.class);
        var published=service.create(principal(TENANT),command("alice"),"API","127.0.0.1");
        assertEquals(original,java.util.HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec.decode(published.address()).publicKeys()));
        assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_key where algorithm='MONERO_NATIVE_2_OF_3'",Integer.class));
    }
    @Test void changedGenesisBlocksNewKeysAndLeavesTheOriginalPublishedAddress() {
        var principal=principal(TENANT);var first=service.create(principal,command("alice"),"API","127.0.0.1");
        when(daemon.network("regtest")).thenReturn(new MoneroDaemonClient.Network("regtest","33".repeat(32),110));
        assertThrows(IllegalStateException.class,()->service.create(principal,command("bob"),"API","127.0.0.1"));
        assertEquals(first.id(),service.create(principal,command("alice"),"API","127.0.0.1").id());
        assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_key where algorithm='MONERO_NATIVE_2_OF_3'",Integer.class));
    }
    @Test void nativeRootsNeverCrossTenantBoundaries() {
        var other=UUID.randomUUID();new CustodyTenantTableRepository(jdbc).insert(other,"monero-other","Other native Monero tenant");
        var first=service.create(principal(TENANT),command("alice"),"API","127.0.0.1");
        var second=service.create(principal(other),command("alice"),"API","127.0.0.1");assertNotEquals(first.address(),second.address());
        assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_key where key_group='XMR_ROOT'",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(distinct tenant_id) from wallet_mpc_key where key_group='XMR_ROOT'",Integer.class));
    }
    @Test void accountObservationRejectsAnotherTenantRootNetworkOrPublicAddressBeforeTransport() {
        var address=service.create(principal(TENANT),command("alice"),"API","127.0.0.1");
        var owner=new ChainAddressRepository(jdbc).listMpcAddresses("XMR").getFirst();
        var point=new com.surprising.wallet.common.chain.ChainScanPoint(100,100,"22".repeat(32));
        var anchor=new MoneroScanAnchorService.Anchor("regtest","11".repeat(32),point,"00".repeat(32));
        assertThrows(IllegalArgumentException.class,()->mpc.signing.observeMonero(UUID.randomUUID(),owner.getMpcKeyId(),address.address(),90,anchor,null,mpc.nodes));
        UUID root=jdbc.queryForObject("select id from wallet_mpc_key where key_group='XMR_ROOT'",UUID.class);
        assertThrows(IllegalStateException.class,()->mpc.signing.observeMonero(TENANT,root,address.address(),90,anchor,null,mpc.nodes));
        var wrong=new MoneroScanAnchorService.Anchor("regtest","33".repeat(32),point,"00".repeat(32));
        assertThrows(IllegalStateException.class,()->mpc.signing.observeMonero(TENANT,owner.getMpcKeyId(),address.address(),90,wrong,null,mpc.nodes));
        assertThrows(IllegalStateException.class,()->mpc.signing.observeMonero(TENANT,owner.getMpcKeyId(),"another-address",90,anchor,null,mpc.nodes));
        assertThrows(IllegalStateException.class,()->mpc.signing.observeMonero(TENANT,owner.getMpcKeyId(),address.address(),-1,anchor,null,mpc.nodes));
    }
}

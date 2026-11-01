package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.chain.monero.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 atomic deposit/projection/cursor boundaries; native transport facts are explicitly substituted. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class MoneroDepositIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String GENESIS="11".repeat(32),TX="22".repeat(32),SCANNER=MoneroScanAnchorService.SCANNER;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicLong height=new AtomicLong(102);
    private final AtomicBoolean unlocked=new AtomicBoolean(true),fork=new AtomicBoolean();
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private MoneroDepositScanner scanner;
    private MoneroDaemonClient daemon;
    private MpcSigningService signing;
    private MpcSigningTestSupport mpc;
    private ChainAddressRecord owner;
    private List<ObjectNode> outputs;
    private AccountChainProfile profile;
    private Runnable observationHook=()->{};

    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='XMR'");
        jdbc.update("update chain_profile set enabled=true,scan_enabled=true,deposit_confirmations=1,scan_max_blocks_per_run=1 where chain='XMR' and network='regtest'");
        context=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),json);mpc=new MpcSigningTestSupport(jdbc);owner=publish(42,1);
        var heights=new ChainScanHeightRepository(jdbc);var blocks=new ChainScanBlockRepository(jdbc);
        heights.initializeNative("XMR",SCANNER,GENESIS,100);blocks.insertNative("XMR",SCANNER,new ChainScanPoint(100,100,hash(100)),hash(99));
        daemon=mock(MoneroDaemonClient.class);signing=mock(MpcSigningService.class);
        when(daemon.network("regtest")).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new MoneroDaemonClient.Network("regtest",GENESIS,height.get());});
        when(daemon.header(eq("regtest"),anyLong())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());long h=call.getArgument(1);return new MoneroDaemonClient.Header(h,fork.get() && h==101?"ff".repeat(32):hash(h),hash(h-1));});
        outputs=new ArrayList<>(List.of(output(1,"1000000000000","33".repeat(32))));
        when(signing.observeMonero(any(),any(),anyString(),anyLong(),any(),isNull(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());observationHook.run();
            var anchor=(MoneroScanAnchorService.Anchor)call.getArgument(4);long from=call.getArgument(3);String sender=call.getArgument(2);
            var policy=json.createObjectNode().put("kind","SNAPSHOT").put("network","regtest").put("genesisHash",GENESIS)
                    .put("sender",sender).put("fromHeight",from).put("height",anchor.point().height()).put("blockHash",anchor.point().hash());
            var request=new MpcClient.Request(UUID.randomUUID(),TENANT,owner.getMpcKeyId(),1,MpcClient.Operation.MONERO_OBSERVE,
                    MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,mpc.nodes.online(),HexFormat.of().formatHex(json.writeValueAsBytes(policy)));
            var facts=json.createObjectNode().put("kind","SNAPSHOT").put("address",sender).put("viewHeight",height.get()-1).put("viewHash",hash(height.get()-1));
            var selected=facts.putArray("outputs");
            for(var output:outputs)if(output.path("blockHeight").asLong()>=from && output.path("blockHeight").asLong()<=anchor.point().height())
                selected.add(output.deepCopy().put("unlocked",unlocked.get()).put("confirmations",height.get()-101));
            var result=new MpcClient.Result(MpcClient.State.COMPLETED,request.sessionId(),TENANT,owner.getMpcKeyId(),1,"aa".repeat(32),"bb".repeat(64),null,sender,facts);
            return new MpcSigningService.MoneroSnapshot(request,result);
        });
        var profiles=new ChainProfileRepository(jdbc);profile=profiles.findAccountByChain("XMR").orElseThrow();
        scanner=new MoneroDepositScanner(daemon,signing,mock(MoneroSubmissionService.class),mpc.nodes,profiles,new WalletMpcKeyEpochRepository(jdbc,json),new ChainAddressRepository(jdbc),
                heights,blocks,new DepositRecordRepository(jdbc),context.getBean(DepositService.class),mpc.manager,json,
                new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc)));
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private static String hash(long height){return String.format("%064x",height+1);}
    private ChainAddressRecord publish(long user,long index) {
        int namespace=new CustodyTenantTableRepository(jdbc).findById(TENANT).orElseThrow().derivationNamespace();
        var key=mpc.signing.awaitMoneroKey(TENANT,user,namespace,index,"regtest",GENESIS,mpc.nodes);
        String address=MoneroPublicAddressCodec.encode(HexFormat.of().parseHex(key.publicKeyHex()),"regtest",true);
        var row=new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(TENANT).mpcKeyId(key.id()).chain("XMR").assetSymbol("XMR")
                .userId(user).biz(namespace).addressIndex(index).address(address).ownerAddress(address).accountId(address).walletRole("DEPOSIT").derivationPath("mpc:"+key.id()).enabled(true).build());
        DepositTestSupport.attachCustodyAddress(jdbc,TENANT,"XMR","XMR",address);return row;
    }
    private ObjectNode output(int index,String amount,String key) {
        return json.createObjectNode().put("transactionHash",TX).put("outputIndex",index).put("outputKey",key).put("globalIndex",index+100)
                .put("amountAtomic",amount).put("blockHeight",101).put("blockHash",hash(101)).put("confirmations",1)
                .put("coinbase",false).put("unlockTime",0).put("spent",false).put("frozen",false).put("unlocked",true)
                .put("change",false).putNull("sourceAddress");
    }
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
    private long cursor(){return new ChainScanHeightRepository(jdbc).nativeState("XMR",SCANNER,false).orElseThrow().safeHeight();}
    private BigDecimal balance(){return new LedgerBalanceRepository(jdbc).find(TENANT,"XMR","XMR",owner.getAccountId()).map(row->row.getTotalBalance()).orElse(BigDecimal.ZERO);}

    @Test void canonicalOutputCreditsMandatoryProjectionAndReplaysOnce() {
        assertEquals(1,scanner.scanAndCredit(profile).size());assertEquals(101,cursor());assertEquals(0,BigDecimal.ONE.compareTo(balance()));
        assertEquals(1,count("deposit_record"));assertEquals(1,count("custody_deposit"));
        assertTrue(scanner.scanAndCredit(profile).isEmpty());assertEquals(0,scanner.confirmObserved());assertEquals(1,count("custody_deposit"));
    }
    @Test void lockedOutputKeepsRealDepthAndUnlocksAfterNewScanningTenantAndAddressClose() {
        unlocked.set(false);assertTrue(scanner.scanAndCredit(profile).isEmpty());assertEquals(101,cursor());assertEquals(0,balance().signum());
        assertEquals(1,jdbc.queryForObject("select confirmations from deposit_record",Integer.class));
        assertEquals("CONFIRMING",jdbc.queryForObject("select status from deposit_record",String.class));
        jdbc.update("update chain_profile set enabled=false,scan_enabled=false where chain='XMR'");
        jdbc.update("update custody_tenant set status='SUSPENDED' where id=?",TENANT);
        jdbc.update("update chain_address set enabled=false where id=?",owner.getId());
        unlocked.set(true);height.set(114);assertEquals(1,scanner.confirmObserved());assertEquals(0,scanner.confirmObserved());
        assertEquals(0,BigDecimal.ONE.compareTo(balance()));assertEquals(101,cursor());assertEquals(1,count("custody_deposit"));
    }
    @Test void changeIsNeverCreditedAsAnExternalDeposit() {
        outputs.getFirst().put("change",true).put("sourceAddress",owner.getAddress());
        assertTrue(scanner.scanAndCredit(profile).isEmpty());assertEquals(0,count("deposit_record"));assertEquals(101,cursor());
    }
    @Test void clearingAFreezeCanCreditWithoutAnotherConfirmation() {
        height.set(114);outputs.getFirst().put("frozen",true);
        assertTrue(scanner.scanAndCredit(profile).isEmpty());assertEquals(13,jdbc.queryForObject("select confirmations from deposit_record",Integer.class));
        outputs.getFirst().put("frozen",false);assertEquals(1,scanner.confirmObserved());assertEquals(0,BigDecimal.ONE.compareTo(balance()));
    }
    @Test void permanentlyLockedOutputsDoNotStarveLaterMaturityChecks() {
        unlocked.set(false);scanner.scanAndCredit(profile);
        var deposits=context.getBean(DepositService.class);String second="66".repeat(32);
        var event=new DepositEvent(ChainType.XMR,"XMR",second,null,owner.getAddress(),BigDecimal.ONE,101,hash(101),1,null,"{}");
        deposits.recordAndCredit(event,1,1,owner.getAccountId(),false);
        var records=new DepositRecordRepository(jdbc);var first=records.listPendingMaturity("XMR",1).getFirst();assertEquals(TX,first.txHash());
        var refresh=new DepositEvent(ChainType.XMR,"XMR",first.txHash(),null,owner.getAddress(),first.amount(),first.blockHeight(),first.blockHash(),first.confirmations(),null,first.rawPayload());
        deposits.recordAndCredit(refresh,first.logIndex(),1,owner.getAccountId(),false);
        assertEquals(second,records.listPendingMaturity("XMR",1).getFirst().txHash());assertEquals(0,balance().signum());
    }
    @Test void originalOutputPositionsRemainStableForMultipleOutputsInOneTransaction() {
        outputs.add(output(3,"2000000000000","44".repeat(32)));
        assertEquals(2,scanner.scanAndCredit(profile).size());assertEquals(List.of(1L,3L),jdbc.queryForList("select log_index from deposit_record order by log_index",Long.class));
        assertEquals(0,new BigDecimal("3").compareTo(balance()));assertEquals(2,count("custody_deposit"));
    }
    @Test void missingCustodyOwnerRollsBackBalanceDepositAndCursor() {
        jdbc.update("delete from custody_address where tenant_id=? and chain='XMR'",TENANT);
        assertThrows(IllegalStateException.class,()->scanner.scanAndCredit(profile));
        assertEquals(0,count("deposit_record"));assertEquals(0,count("ledger_balance"));assertEquals(100,cursor());
    }
    @Test void projectionFailureRollsBackTheWholeNativeBatch() {
        jdbc.execute("create function xmr_test_reject() returns trigger language plpgsql as $$ begin raise exception 'reject projection';end $$;create trigger xmr_test_reject before insert on custody_deposit for each row execute function xmr_test_reject()");
        try{assertThrows(RuntimeException.class,()->scanner.scanAndCredit(profile));}
        finally{jdbc.execute("drop trigger xmr_test_reject on custody_deposit;drop function xmr_test_reject()");}
        assertEquals(0,count("deposit_record"));assertEquals(0,count("ledger_balance"));assertEquals(100,cursor());
    }
    @Test void reorgReversesCanonicalCreditWhenTheChainIsDisabled() {
        scanner.scanAndCredit(profile);fork.set(true);jdbc.update("update chain_profile set enabled=false where chain='XMR'");
        assertEquals(1,scanner.reconcileObserved());assertEquals(100,cursor());assertEquals(0,balance().signum());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where action='DEPOSIT.REORG'",Integer.class));
        assertEquals(0,scanner.reconcileObserved());
    }
    @Test void reorgFailureRetainsTheOriginalCreditAndCursor() {
        scanner.scanAndCredit(profile);fork.set(true);
        jdbc.execute("create function xmr_test_reject() returns trigger language plpgsql as $$ begin raise exception 'reject rewind';end $$;create trigger xmr_test_reject before delete on chain_scan_block for each row execute function xmr_test_reject()");
        try{assertThrows(RuntimeException.class,scanner::reconcileObserved);}
        finally{jdbc.execute("drop trigger xmr_test_reject on chain_scan_block;drop function xmr_test_reject()");}
        assertEquals(101,cursor());assertEquals(0,BigDecimal.ONE.compareTo(balance()));
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_audit_log where action='DEPOSIT.REORG'",Integer.class));
    }
    @Test void concurrentAddressPublicationPreventsSkippingTheNewAccount() {
        observationHook=()->{publish(43,2);observationHook=()->{};};
        assertThrows(IllegalStateException.class,()->scanner.scanAndCredit(profile));assertEquals(100,cursor());assertEquals(0,count("deposit_record"));
    }
    @Test void changedNativeGenesisCannotModifyAnExistingLedger() {
        when(daemon.network("regtest")).thenReturn(new MoneroDaemonClient.Network("regtest","55".repeat(32),102));
        assertThrows(IllegalStateException.class,()->scanner.scanAndCredit(profile));assertEquals(100,cursor());assertEquals(0,count("deposit_record"));
    }
}

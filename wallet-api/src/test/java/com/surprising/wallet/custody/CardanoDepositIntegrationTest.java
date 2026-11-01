package com.surprising.wallet.custody;

import com.surprising.wallet.chain.cardano.*;
import com.surprising.wallet.chain.model.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.CardanoSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.sync.Sync;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 transactions over captured native blocks; public ownership/RPC context is controlled here. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CardanoDepositIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String GENESIS = "11".repeat(32), SCANNER = CardanoSubmissionService.SCANNER;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private CardanoRpcClient rpc;
    private CardanoSubmissionService submissions;
    private CardanoDepositScanner scanner;
    private ChainScanHeightRepository heights;
    private ChainScanBlockRepository blocks;
    private CardanoOwnedOutputRepository owned;
    private ChainScanPoint anchor, point;
    private ChainAddressRecord receiver;
    private JsonNode vector;
    private MpcCardanoTransactionCodec.Unsigned unsigned;
    private Sync.AnyChainBlock original;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource(); CustodyIntegrationDatabase.reset(ds); jdbc = new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='ADA'");
        jdbc.update("update chain_profile set enabled=true,scan_enabled=true,deposit_confirmations=3,scan_max_blocks_per_run=50 where chain='ADA' and network='preprod'");
        jdbc.update("update chain_asset set active=true where chain='ADA' and symbol='ADA'");
        context = DepositTestSupport.context(jdbc, CustodyTestSupport.events(jdbc), json);
        rpc = mock(CardanoRpcClient.class); submissions = mock(CardanoSubmissionService.class);
        heights = new ChainScanHeightRepository(jdbc); blocks = new ChainScanBlockRepository(jdbc); owned = new CardanoOwnedOutputRepository(jdbc);
    }
    @AfterEach void close() { if (context != null) context.close(); }

    private void configure(int index) throws Exception {
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-original-block-reference.json")) { vector = json.readTree(resource).path("vectors").get(index); }
        unsigned = json.treeToValue(vector.path("unsigned"), MpcCardanoTransactionCodec.Unsigned.class);
        original = Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(vector.path("blockBase64").asText()));
        var verified = CardanoExecutionReceipt.verifyBlock(original); point = CardanoRpcClient.point(verified.reference());
        anchor = new ChainScanPoint(point.height() - 1, point.slot() - 1, verified.parentHash());
        var fixture = new MpcSigningTestSupport(jdbc);
        var namespace = new CustodyTenantTableRepository(jdbc).findById(TENANT).orElseThrow().derivationNamespace();
        var key = fixture.signing.awaitKey(TENANT, ChainType.ADA, 42, namespace, 1, fixture.nodes);
        // This scanner fixture assigns captured public output ownership at first publication; it never signs with this key.
        receiver = new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(TENANT).mpcKeyId(key.id())
                .chain("ADA").assetSymbol("ADA").userId(42L).biz(namespace).addressIndex(1L).address(unsigned.recipient())
                .ownerAddress(unsigned.recipient()).accountId("ada-recipient").walletRole("DEPOSIT").derivationPath("mpc:"+key.id()).enabled(true).build());
        DepositTestSupport.attachCustodyAddress(jdbc, TENANT, "ADA", "ADA", receiver.getAddress());
        for (String symbol : List.of("USDT", "USDC")) {
            String name = symbol.equals("USDT") ? "55534454" : "55534443";
            // Captured public fixture policy; only the selected token is present in its receiver output.
            String policy = unsigned.unit().equals("lovelace") ? "ab".repeat(28) : unsigned.unit().substring(0,56);
            jdbc.update("update token_config set enabled=true,contract_address=? where chain='ADA' and network='preprod' and symbol=?", policy + "." + name, symbol);
            var token = ChainAddressRecord.builder().tenantId(TENANT).mpcKeyId(receiver.getMpcKeyId()).chain("ADA").assetSymbol(symbol)
                    .accountId("recipient-" + symbol).userId(receiver.getUserId()).biz(receiver.getBiz()).addressIndex(receiver.getAddressIndex())
                    .address(receiver.getAddress()).ownerAddress(receiver.getAddress()).derivationPath(receiver.getDerivationPath()).walletRole("DEPOSIT").enabled(true).build();
            new ChainAddressRepository(jdbc).publishMpc(token);
        }
        heights.initializeNative("ADA", SCANNER, GENESIS, anchor.height()); blocks.insertNative("ADA", SCANNER, anchor, null);
        when(rpc.genesis()).thenReturn(Query.ReadGenesisResponse.newBuilder().setGenesis(com.google.protobuf.ByteString.copyFrom(HexFormat.of().parseHex(GENESIS)))
                .setCardano(utxorpc.v1beta.cardano.Cardano.Genesis.getDefaultInstance()).build());
        when(rpc.commonPoint(anyList())).thenAnswer(call -> ((List<ChainScanPoint>) call.getArgument(0)).getFirst());
        when(rpc.tip()).thenReturn(CardanoRpcClient.reference(new ChainScanPoint(point.height()+2,point.slot()+5,"ff".repeat(32))));
        when(rpc.blocksAfter(any(), anyLong(), anyInt())).thenAnswer(call -> CardanoRpcClient.point(call.getArgument(0)).equals(anchor) ? List.of(original) : List.of());
        scanner = new CardanoDepositScanner(rpc, new ChainAddressRepository(jdbc), new ChainProfileRepository(jdbc), new TokenConfigRepository(jdbc),
                new ChainAssetRepository(jdbc), heights, blocks, owned, submissions, context.getBean(DepositService.class), fixture.manager, json,
                new WalletRuntimeConfigService(new ChainProfileRepository(jdbc), new WalletSystemConfigRepository(jdbc)));
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private void assertAt(ChainScanPoint expected) { assertEquals(expected.height(), heights.nativeState("ADA",SCANNER,false).orElseThrow().safeHeight()); }
    private void internalInput(int indexOffset) {
        var input = unsigned.inputs().getFirst();
        owned.insert(new CardanoOwnedOutput(input.transactionId(), input.index()+indexOffset, TENANT, receiver.getId(), receiver.getAddress(),
                anchor.height(),anchor.hash(),input.outputHex()));
    }

    @ParameterizedTest @ValueSource(ints={0,1,2})
    void nativeAndTokensCreditMandatoryProjectionAndResumeWithoutDoubleCredit(int index) throws Exception {
        configure(index); var events = scanner.scanAndCredit();
        assertEquals(index == 0 ? 1 : 2, events.size()); assertEquals(events.size(),count("custody_deposit"));
        assertAt(point); assertEquals(1,count("cardano_owned_output"));
        for (var event : events) {
            assertEquals(point.hash(),event.blockHash()); assertNull(event.fromAddress());
            assertEquals(0,event.amount().compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"ADA",event.assetSymbol(),
                    event.assetSymbol().equals("ADA")?receiver.getAccountId():"recipient-"+event.assetSymbol()).orElseThrow().getAvailableBalance()));
        }
        assertTrue(scanner.scanAndCredit().isEmpty()); assertEquals(events.size(),count("deposit_record"));
    }
    @Test void missingCustodyOwnerRollsBackBalanceOutputAndCursor() throws Exception {
        configure(1); jdbc.update("delete from custody_address where tenant_id=? and chain='ADA'",TENANT);
        assertThrows(IllegalStateException.class,scanner::scanAndCredit);
        assertEquals(0,count("deposit_record")); assertEquals(0,count("ledger_balance")); assertEquals(0,count("cardano_owned_output")); assertAt(anchor);
    }
    @Test void downstreamProjectionErrorRollsBackEntireBlock() throws Exception {
        configure(1);
        jdbc.execute("create function cardano_test_reject_credit() returns trigger language plpgsql as $$ begin raise exception 'reject projection';end $$;create trigger cardano_test_reject_credit before insert on custody_deposit for each row execute function cardano_test_reject_credit()");
        try { assertThrows(RuntimeException.class,scanner::scanAndCredit); }
        finally { jdbc.execute("drop trigger cardano_test_reject_credit on custody_deposit;drop function cardano_test_reject_credit()"); }
        assertEquals(0,count("deposit_record")); assertEquals(0,count("ledger_balance")); assertEquals(0,count("cardano_owned_output")); assertAt(anchor);
    }
    @Test void reorgReversesExistingCreditAndCursorWhenNewScanningIsDisabled() throws Exception {
        configure(1); int credited=scanner.scanAndCredit().size();
        jdbc.update("update chain_profile set scan_enabled=false where chain='ADA'");
        doReturn(anchor).when(rpc).commonPoint(anyList());
        assertEquals(credited,scanner.reconcileObserved()); assertAt(anchor);
        assertEquals(0,count("cardano_owned_output")); assertEquals(1,count("chain_scan_block"));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where total_balance<>0",Integer.class));
        assertEquals(credited,jdbc.queryForObject("select count(*) from custody_audit_log where action='DEPOSIT.REORG'",Integer.class));
        assertEquals(0,scanner.reconcileObserved());
    }
    @Test void reorgFailureRollsBackReversalAndKeepsOriginalCursor() throws Exception {
        configure(0); scanner.scanAndCredit(); doReturn(anchor).when(rpc).commonPoint(anyList());
        jdbc.execute("create function cardano_test_reject_rewind() returns trigger language plpgsql as $$ begin raise exception 'reject rewind';end $$;create trigger cardano_test_reject_rewind before delete on cardano_owned_output for each row execute function cardano_test_reject_rewind()");
        try { assertThrows(RuntimeException.class,scanner::reconcileObserved); }
        finally { jdbc.execute("drop trigger cardano_test_reject_rewind on cardano_owned_output;drop function cardano_test_reject_rewind()"); }
        assertAt(point); assertEquals(1,count("cardano_owned_output"));
        assertEquals(1,jdbc.queryForObject("select count(*) from ledger_balance where total_balance>0",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_audit_log where action='DEPOSIT.REORG'",Integer.class));
    }
    @Test void internalInputsUseExactOutputReferences() throws Exception {
        configure(0); internalInput(0);
        assertTrue(scanner.scanAndCredit().isEmpty()); assertEquals(0,count("deposit_record")); assertEquals(2,count("cardano_owned_output")); assertAt(point);
    }
    @Test void unrelatedOutputFromSameTransactionDoesNotHideAnExternalDeposit() throws Exception {
        configure(0); internalInput(1); assertEquals(1,scanner.scanAndCredit().size());
    }
    @Test void explicitWithdrawalToAnotherOwnedDepositIsCredited() throws Exception {
        configure(1); internalInput(0);
        var payload = new CardanoSigningPayload("USDT",BigDecimal.ONE,BigDecimal.ZERO,6,3,anchor,unsigned);
        when(submissions.withdrawal(TENANT,vector.path("selected").path("txHash").asText())).thenReturn(Optional.of(payload));
        assertEquals(2,scanner.scanAndCredit().size());
    }
    @Test void changedNetworkOrUnknownDeepForkCannotChangeLedgerOrCursor() throws Exception {
        configure(0); scanner.scanAndCredit();
        doThrow(new IllegalStateException("no stored common header")).when(rpc).commonPoint(anyList());
        assertThrows(IllegalStateException.class,scanner::reconcileObserved); assertAt(point);
        when(rpc.genesis()).thenReturn(Query.ReadGenesisResponse.newBuilder().setGenesis(com.google.protobuf.ByteString.copyFrom(new byte[32]))
                .setCardano(utxorpc.v1beta.cardano.Cardano.Genesis.getDefaultInstance()).build());
        assertThrows(IllegalStateException.class,scanner::reconcileObserved); assertAt(point); assertEquals(1,count("custody_deposit"));
    }

    @Test void onePublishedNativeJointAddressAcceptsAllConfiguredAssetsWithoutTokenAliases() throws Exception {
        configure(1); jdbc.update("delete from chain_address where tenant_id=? and chain='ADA' and asset_symbol<>'ADA'", TENANT);
        var events = scanner.scanAndCredit(); assertEquals(2, events.size());
        var token = events.stream().filter(event -> !event.assetSymbol().equals("ADA")).findFirst().orElseThrow();
        assertEquals(0, token.amount().compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"ADA",token.assetSymbol(),receiver.getAccountId()).orElseThrow().getTotalBalance()));
    }
}

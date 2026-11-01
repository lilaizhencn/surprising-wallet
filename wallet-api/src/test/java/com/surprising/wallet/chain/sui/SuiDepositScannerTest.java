package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.SuiReceiptTestSupport;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real public original execution proof; convenience JSON cannot manufacture deposits. */
class SuiDepositScannerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final SuiRpcClient rpc=mock(SuiRpcClient.class);
    private final ChainProfileRepository profiles=mock(ChainProfileRepository.class);
    private final TokenConfigRepository tokens=mock(TokenConfigRepository.class);
    private final ChainScanHeightRepository heights=mock(ChainScanHeightRepository.class);
    private final ChainAddressRepository addresses=mock(ChainAddressRepository.class);
    private final SuiTransactionRepository transactions=mock(SuiTransactionRepository.class);
    private final DepositService deposits=mock(DepositService.class);
    private final ChainAssetRepository assets=mock(ChainAssetRepository.class);
    private final DepositRecordRepository records=mock(DepositRecordRepository.class);
    private final WalletRuntimeConfigService runtime=mock(WalletRuntimeConfigService.class);
    private final SuiDepositScanner scanner=new SuiDepositScanner(rpc,profiles,tokens,heights,addresses,transactions,deposits,assets,records,runtime);
    private JsonNode item;
    private ChainAddressRecord owner;
    private AccountChainProfile profile;
    @BeforeEach void setup() throws Exception {
        try(var in=getClass().getResourceAsStream("/mpc/sui-actual-native-receipts.json")){item=json.readTree(in).path("payments").get(0);}
        owner=ChainAddressRecord.builder().chain("SUI").assetSymbol("SUI").address(item.path("unsigned").path("recipient").asText()).accountId("owned-sui").walletRole("DEPOSIT").enabled(true).build();
        profile=AccountChainProfile.builder().chain("SUI").network("testnet").enabled(true).depositConfirmations(1).scanBatchSize(50).build();
        when(profiles.findAccountByChain("SUI")).thenReturn(Optional.of(profile));
        when(addresses.listEnabled("SUI")).thenReturn(List.of(owner));
        when(assets.findDefinition("SUI","SUI")).thenReturn(Optional.of(com.surprising.wallet.chain.model.ChainAsset.builder().chain("SUI").symbol("SUI").decimals(9).nativeAsset(true).active(true).build()));
        when(rpc.latestCheckpoint()).thenReturn(item.path("receipt").path("checkpoint").asLong());
        when(rpc.checkpointTransactions(anyLong(),anyLong())).thenReturn(List.of(item.path("receipt")));
    }
    @Test void successfulOriginalProofCreditsExactPrincipalAndPreservesActualSignedFee() {
        var result=scanner.scanAndCredit();assertEquals(1,result.size());assertEquals(0,new BigDecimal("0.1").compareTo(result.getFirst().amount()));
        var stored=ArgumentCaptor.forClass(com.surprising.wallet.chain.model.SuiTransactionRecord.class);
        verify(transactions).upsert(stored.capture());assertEquals(-1_914_600,stored.getValue().getFeeMist());assertNotNull(stored.getValue().getExecutionReceipt());
        verify(deposits).recordAndCredit(any(DepositEvent.class),anyLong(),eq(1),eq("owned-sui"));
    }
    @Test void abortedExecutionNeverCreditsItsPositiveReportedBalance() {
        var tx=SuiExecutionReceipt.decode(item.path("receipt"));var failed=tx.toBuilder().setEffects(tx.getEffects().toBuilder().setStatus(tx.getEffects().getStatus().toBuilder().setSuccess(false))).build();
        when(rpc.checkpointTransactions(anyLong(),anyLong())).thenReturn(List.of(SuiReceiptTestSupport.envelope(failed)));
        assertTrue(scanner.scanAndCredit().isEmpty());verifyNoInteractions(deposits,transactions);
    }
    @Test void internalMpcSenderAndReplayDoNotBecomeExternalDeposits() {
        var source=ChainAddressRecord.builder().chain("SUI").assetSymbol("SUI").address(item.path("unsigned").path("sender").asText()).accountId("sender").walletRole("HOT").enabled(true).build();
        when(addresses.listEnabled("SUI")).thenReturn(List.of(owner,source));assertTrue(scanner.scanAndCredit().isEmpty());verifyNoInteractions(deposits);
        when(addresses.listEnabled("SUI")).thenReturn(List.of(owner));when(records.existsCanonical(eq("SUI"),anyString(),anyLong())).thenReturn(true);
        assertTrue(scanner.scanAndCredit().isEmpty());verifyNoInteractions(deposits);
    }
    @Test void forgedConvenienceAmountAndStatusCannotOverrideOriginalReceipt() {
        var fake=json.createObjectNode().put("executedTransactionBase64",item.path("receipt").path("executedTransactionBase64").asText());
        fake.putObject("effects").putObject("status").put("status","failure");fake.putArray("balanceChanges").addObject().put("amount","99999999999999999999");
        when(rpc.checkpointTransactions(anyLong(),anyLong())).thenReturn(List.of(fake));assertEquals(0,new BigDecimal("0.1").compareTo(scanner.scanAndCredit().getFirst().amount()));
        when(rpc.checkpointTransactions(anyLong(),anyLong())).thenReturn(List.of(json.createObjectNode().put("digest",item.path("selected").path("digest").asText())));
        assertThrows(RuntimeException.class,scanner::scanAndCredit);
    }
    @Test void observedTokenCompletesWithoutNewTaskTokenOrAddressLookup() {
        var hash=item.path("selected").path("digest").asText();long checkpoint=item.path("receipt").path("checkpoint").asLong();
        when(records.listPending("SUI",Integer.MAX_VALUE,500)).thenReturn(List.of(new DepositRecordRepository.PendingRecord("USDC",hash,1,"0x1",owner.getAddress(),"0x2::usdc::USDC",BigDecimal.ONE,checkpoint,hash,1,owner.getAccountId(),item.path("receipt").toString())));
        when(rpc.latestCheckpoint()).thenReturn(checkpoint+2);profile.setDepositConfirmations(3);
        scanner.confirmObserved();var event=ArgumentCaptor.forClass(DepositEvent.class);verify(deposits).recordAndCredit(event.capture(),eq(1L),eq(3),eq("owned-sui"));
        assertEquals("USDC",event.getValue().assetSymbol());assertEquals(3,event.getValue().confirmations());verifyNoInteractions(runtime,tokens,addresses);
        verify(transactions).confirmDeposit(hash);
    }
}

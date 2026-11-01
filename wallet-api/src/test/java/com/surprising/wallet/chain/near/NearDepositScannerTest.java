package com.surprising.wallet.chain.near;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Canonical FINAL effects, including resolved ft_transfer_call refunds; request arguments are never receipts. */
class NearDepositScannerTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixtures()throws Exception{try(var input=getClass().getResourceAsStream("/mpc/near-actual-payment-proofs.json")){return json.readTree(input);}}
    @Test void actualFinalNativeAndTokensIncludeTheSmallestNativeUnit()throws Exception {
        boolean smallest=false;
        for(var item:fixtures().path("payments")) {
            var u=item.path("unsigned");var changes=NearDepositScanner.changes(item.path("receipt"));
            var received=changes.stream().filter(change->change.receiver().equals(u.path("recipient").asText())).findFirst().orElseThrow();
            assertEquals(new BigInteger(u.path("amountAtomic").asText()),received.amount());assertEquals(u.path("sender").asText(),received.sender());
            if(u.path("tokenContract").isNull()){assertNull(received.contract());smallest|=BigInteger.ONE.equals(received.amount());}
            else assertEquals(u.path("tokenContract").asText(),received.contract());
        }
        assertTrue(smallest);
    }
    @Test void actualFinalFailuresAndArgumentsWithoutCommittedTokenEventsNeverCredit()throws Exception {
        for(var item:fixtures().path("failedPayments"))assertTrue(NearDepositScanner.changes(item.path("receipt")).isEmpty());
        var receipt=(ObjectNode)fixtures().path("payments").get(3).path("receipt").deepCopy();
        for(var outcome:receipt.path("receipts_outcome"))((ObjectNode)outcome.path("outcome")).putArray("logs");
        assertTrue(NearDepositScanner.changes(receipt).isEmpty());
        receipt.put("final_execution_status","EXECUTED");assertThrows(IllegalStateException.class,()->NearDepositScanner.changes(receipt));
    }
    @Test void resolvedPartialAndFullTokenRefundsReduceTheDeposit()throws Exception {
        var item=fixtures().path("payments").get(3);String source=item.path("unsigned").path("sender").asText(),receiver=item.path("unsigned").path("recipient").asText();
        var partial=refund(item,400_000,false);var changes=NearDepositScanner.changes(partial);
        assertEquals(1,changes.size());assertEquals(receiver,changes.getFirst().receiver());assertEquals(BigInteger.valueOf(600_000),changes.getFirst().amount());
        assertTrue(NearDepositScanner.changes(refund(item,1_000_000,false)).isEmpty());
        var callbackFailure=NearDepositScanner.changes(refund(item,400_000,true));assertEquals(BigInteger.valueOf(1_000_000),callbackFailure.getFirst().amount());
        assertNotEquals(source,changes.getFirst().receiver());
    }
    @Test void actualFinalTransferCallWithACallbackFailureAndCompletedRefundNeverCreditsItsRequestedAmount()throws Exception {
        JsonNode fixture;try(var input=getClass().getResourceAsStream("/mpc/near-actual-transfer-call-refund.json")){fixture=json.readTree(input);}
        assertEquals("1000000",fixture.path("amountAtomic").asText());var receipt=fixture.path("receipt");
        assertTrue(receipt.path("status").has("SuccessValue"));
        assertTrue(java.util.stream.StreamSupport.stream(receipt.path("receipts_outcome").spliterator(),false).anyMatch(item->item.path("outcome").path("status").has("Failure")));
        assertTrue(NearDepositScanner.changes(receipt).isEmpty());
    }
    private JsonNode refund(JsonNode item,int amount,boolean failed) {
        var result=(ObjectNode)item.path("receipt").deepCopy();var receipt=json.createObjectNode().put("id","resolved-refund");var outcome=receipt.putObject("outcome").put("executor_id",item.path("unsigned").path("tokenContract").asText());
        outcome.putObject("status").put(failed?"Failure":"SuccessValue","");var event=json.createObjectNode().put("standard","nep141").put("version","1.0.0").put("event","ft_transfer");
        event.putArray("data").addObject().put("old_owner_id",item.path("unsigned").path("recipient").asText()).put("new_owner_id",item.path("unsigned").path("sender").asText()).put("amount",Integer.toString(amount));
        outcome.putArray("logs").add("EVENT_JSON:"+event);result.withArray("receipts_outcome").add(receipt);return result;
    }
    @Test void canonicalReplayAndDisabledInternalSendersCannotCreditAgain()throws Exception {
        var item=fixtures().path("payments").get(3);var f=new Fixture(item);assertEquals(1,f.scanner.scanAndCredit().size());
        var event=ArgumentCaptor.forClass(DepositEvent.class);verify(f.deposits).recordAndCredit(event.capture(),anyLong(),eq(1),eq("receiver-account"));
        assertEquals(0,BigDecimal.ONE.compareTo(event.getValue().amount()));assertEquals(item.path("unsigned").path("tokenContract").asText(),event.getValue().tokenAddress());
        when(f.records.existsCanonical(eq("NEAR"),anyString(),anyLong())).thenReturn(true);assertTrue(f.scanner.scanAndCredit().isEmpty());verifyNoMoreInteractions(f.deposits);
        var internal=new Fixture(item);when(internal.addresses.existsAddress("NEAR",item.path("unsigned").path("sender").asText())).thenReturn(true);
        assertTrue(internal.scanner.scanAndCredit().isEmpty());verifyNoInteractions(internal.deposits);verify(internal.rpc,never()).finalTransaction(anyString(),anyString());
    }
    @Test void duplicateOutcomesOrUnprovedMissingBlocksCannotAdvanceTheCursor()throws Exception {
        var item=fixtures().path("payments").get(3);var changed=(ObjectNode)item.path("receipt").deepCopy();changed.withArray("receipts_outcome").add(changed.path("receipts_outcome").get(0));
        assertThrows(IllegalStateException.class,()->NearDepositScanner.changes(changed));
        var f=new Fixture(item);when(f.rpc.block(100)).thenThrow(new IllegalStateException("UNKNOWN_BLOCK"));assertThrows(IllegalStateException.class,f.scanner::scanAndCredit);
        verify(f.heights,never()).upsert(anyString(),anyString(),anyLong(),anyLong());verifyNoInteractions(f.deposits);
    }
    private final class Fixture {
        final NearRpcClient rpc=mock(NearRpcClient.class);final ChainScanHeightRepository heights=mock(ChainScanHeightRepository.class);
        final ChainAddressRepository addresses=mock(ChainAddressRepository.class);final DepositRecordRepository records=mock(DepositRecordRepository.class);
        final DepositService deposits=mock(DepositService.class);final NearDepositScanner scanner;
        Fixture(JsonNode item) {
            var profiles=mock(ChainProfileRepository.class);var tokens=mock(TokenConfigRepository.class);var runtime=mock(WalletRuntimeConfigService.class);
            when(profiles.findAccountByChain("NEAR")).thenReturn(Optional.of(AccountChainProfile.builder().chain("NEAR").network("localnet").depositConfirmations(1).scanMaxBlocksPerRun(1L).build()));
            when(rpc.latestFinalBlockHeight()).thenReturn(100L);when(heights.findSafeHeight("NEAR","near-block-scanner")).thenReturn(Optional.of(99L));
            var address=ChainAddressRecord.builder().tenantId(UUID.randomUUID()).chain("NEAR").assetSymbol("NEAR").address(item.path("unsigned").path("recipient").asText()).walletRole("DEPOSIT").accountId("receiver-account").enabled(true).build();
            when(addresses.listEnabled("NEAR","NEAR")).thenReturn(List.of(address));
            when(tokens.listDefinitions("NEAR","localnet")).thenReturn(List.of(TokenDefinition.builder().chain("NEAR").symbol("USDC").contractAddress(item.path("unsigned").path("tokenContract").asText()).decimals(6).active(true).build()));
            var block=json.createObjectNode();block.putObject("header").put("height",100).put("hash","canonical");block.putArray("chunks").addObject().put("height_included",100).put("chunk_hash","chunk").put("tx_root","nonempty");when(rpc.block(100)).thenReturn(block);
            var chunk=json.createObjectNode();chunk.putArray("transactions").add(item.path("receipt").path("transaction"));when(rpc.chunk("chunk")).thenReturn(chunk);
            when(rpc.finalTransaction(anyString(),anyString())).thenReturn(item.path("receipt"));
            when(rpc.block(anyString())).thenAnswer(invocation->{var value=json.createObjectNode();value.putObject("header").put("hash",invocation.getArgument(0,String.class)).put("height",100);return value;});
            scanner=new NearDepositScanner(rpc,heights,mock(NearTransactionRepository.class),deposits,addresses,profiles,tokens,records,runtime);
        }
    }
}

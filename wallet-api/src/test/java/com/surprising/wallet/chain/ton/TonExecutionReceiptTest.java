package com.surprising.wallet.chain.ton;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class TonExecutionReceiptTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode fixture(String asset)throws Exception{try(var in=getClass().getResourceAsStream("/mpc/ton-actual-"+asset+"-receipt.json")){return JSON.readTree(in);}}
    private static MpcTonTransactionCodec.Unsigned payment(JsonNode f){return JSON.treeToValue(f.path("unsigned"),MpcTonTransactionCodec.Unsigned.class);}
    private static MpcTonTransactionCodec.Signed selected(JsonNode f){return JSON.treeToValue(f.path("selected"),MpcTonTransactionCodec.Signed.class);}
    private static TonExecutionReceipt.Trace trace(JsonNode f){return f.has("receipt")?new TonExecutionReceipt.Trace(f.path("receipt"),null,f.path("recipient"),List.of()):
            new TonExecutionReceipt.Trace(f.path("owner"),f.path("sourceJetton"),f.path("recipientJetton"),JSON.convertValue(f.path("returns"),JSON.getTypeFactory().constructCollectionType(List.class,JsonNode.class)));}
    @Test void actualNativeCreditAndJettonCreditIncludeExactSourceCostAndExcess()throws Exception{
        for(String asset:List.of("native","usdt","usdc")){
            var f=fixture(asset);var result=TonExecutionReceipt.verifySuccessful(trace(f),payment(f),selected(f));
            assertTrue(result.logicalTime().signum()>0);assertTrue(result.feeNano()>0);
            if(!"native".equals(asset))assertEquals(f.path("sourceTonBefore").asLong()-f.path("sourceTonAfter").asLong(),result.feeNano());
        }
    }
    @Test void anotherValidReceiptCannotProveThisOwnedPayment()throws Exception{
        var usdt=fixture("usdt");var usdc=fixture("usdc");var original=trace(usdt);
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(
                new TonExecutionReceipt.Trace(original.owner(),original.sourceJetton(),trace(usdc).recipient(),original.refunds()),payment(usdt),selected(usdt)));
    }
    @Test void missingOrDuplicatedRefundCannotChangeActualFee()throws Exception{
        var f=fixture("usdt");var t=trace(f);assertFalse(t.refunds().isEmpty());
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(
                new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),t.recipient(),List.of()),payment(f),selected(f)));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(
                new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),t.recipient(),List.of(t.refunds().getFirst(),t.refunds().getFirst())),payment(f),selected(f)));
    }
    @Test void rawHashSubstitutionAndMissingTargetHoldPayment()throws Exception{
        var f=fixture("usdc");var t=trace(f);ObjectNode forged=(ObjectNode)t.recipient().deepCopy();
        ((ObjectNode)forged.path("transaction_id")).put("hash","00".repeat(32));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(
                new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),forged,t.refunds()),payment(f),selected(f)));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(
                new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),null,t.refunds()),payment(f),selected(f)));
    }
    @Test void undeployedOwnerNotificationDoesNotUndoProvenJettonWalletCredit()throws Exception{
        var f=fixture("usdt");var t=trace(f);
        assertTrue(t.recipient().path("out_msgs").size()>=2);
        // The actual fixture's notification recipient has no deployed TON account. No owner notification receipt is supplied.
        assertEquals(1,t.refunds().size());
        assertDoesNotThrow(()->TonExecutionReceipt.verifySuccessful(t,payment(f),selected(f)));
    }
    @Test void skippedSendConsumesSequenceWithoutPayingPrincipalAndKeepsActualFee()throws Exception{
        var f=fixture("skipped");var t=new TonExecutionReceipt.Trace(f.path("receipt"),null,null,List.of());
        var result=TonExecutionReceipt.verifySkippedPayment(t,payment(f),selected(f));
        assertEquals("TON_SEND_SKIPPED",result.reason());assertTrue(result.feeNano()>0);assertTrue(result.logicalTime().signum()>0);
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(t,payment(f),selected(f)));
    }
    @Test void paidOrUnrelatedExecutionsCannotProveZeroPayment()throws Exception{
        var paid=fixture("native");
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySkippedPayment(trace(paid),payment(paid),selected(paid)));
        var skipped=fixture("skipped");
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySkippedPayment(
                new TonExecutionReceipt.Trace(paid.path("receipt"),null,null,List.of()),payment(skipped),selected(skipped)));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySkippedPayment(
                new TonExecutionReceipt.Trace(skipped.path("receipt"),null,paid.path("recipient"),List.of()),payment(skipped),selected(skipped)));
    }
    @Test void abortedOwnerCannotReleasePrincipalEvenWithNoOutgoingMessages()throws Exception{
        var f=fixture("skipped");var raw=org.ton.ton4j.cell.Cell.fromBocBase64(f.path("receipt").path("data").asText());
        var transaction=org.ton.ton4j.tlb.Transaction.deserialize(org.ton.ton4j.cell.CellSlice.beginParse(raw));
        ((org.ton.ton4j.tlb.TransactionDescriptionOrdinary)transaction.getDescription()).setAborted(true);
        var changed=com.surprising.wallet.testutil.TonReceiptTestSupport.serialize(transaction,raw);var receipt=(ObjectNode)f.path("receipt").deepCopy();
        receipt.put("data",java.util.Base64.getEncoder().encodeToString(changed.toBoc(false)));
        ((ObjectNode)receipt.path("transaction_id")).put("hash",HexFormat.of().formatHex(changed.hash()));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySkippedPayment(
                new TonExecutionReceipt.Trace(receipt,null,null,List.of()),payment(f),selected(f)));
    }
    @Test void persistedNativeAndSkippedTracesRetainAbsentHops()throws Exception{
        var nativeFixture=fixture("native");var paid=JSON.readValue(JSON.writeValueAsString(trace(nativeFixture)),TonExecutionReceipt.Trace.class);
        assertNull(paid.sourceJetton());assertDoesNotThrow(()->TonExecutionReceipt.verifySuccessful(paid,payment(nativeFixture),selected(nativeFixture)));
        var skipped=fixture("skipped");var failed=JSON.readValue(JSON.writeValueAsString(new TonExecutionReceipt.Trace(skipped.path("receipt"),null,null,List.of())),TonExecutionReceipt.Trace.class);
        assertNull(failed.sourceJetton());assertNull(failed.recipient());assertDoesNotThrow(()->TonExecutionReceipt.verifySkippedPayment(failed,payment(skipped),selected(skipped)));
    }
    @Test void actualSourceRejectionProvesNoTokenPaymentAndExactBounceExpense()throws Exception{
        var f=fixture("rejected");var result=TonExecutionReceipt.verifyRejectedJetton(trace(f),payment(f),selected(f));
        assertEquals("TON_JETTON_SOURCE_REJECTED",result.reason());assertTrue(result.feeNano()>0);
        assertEquals(f.path("sourceTonBefore").asLong()-f.path("sourceTonAfter").asLong(),result.feeNano());
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifySuccessful(trace(f),payment(f),selected(f)));
    }
    @Test void rejectedJettonRequiresItsExactBounceRefundAndNoTargetPayment()throws Exception{
        var f=fixture("rejected");var t=trace(f);
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifyRejectedJetton(new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),null,List.of()),payment(f),selected(f)));
        var other=trace(fixture("usdc"));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifyRejectedJetton(new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),null,other.refunds()),payment(f),selected(f)));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifyRejectedJetton(new TonExecutionReceipt.Trace(t.owner(),t.sourceJetton(),other.recipient(),t.refunds()),payment(f),selected(f)));
        assertThrows(IllegalStateException.class,()->TonExecutionReceipt.verifyRejectedJetton(other,payment(f),selected(f)));
    }
}

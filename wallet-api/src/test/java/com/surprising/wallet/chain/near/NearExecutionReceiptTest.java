package com.surprising.wallet.chain.near;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Original public FINAL outcomes captured from actual MPC; no wallet key is reconstructed. */
class NearExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixtures()throws Exception {try(var input=getClass().getResourceAsStream("/mpc/near-actual-payment-proofs.json")){return json.readTree(input).path("payments");}}
    private JsonNode failed()throws Exception {try(var input=getClass().getResourceAsStream("/mpc/near-actual-payment-proofs.json")){return json.readTree(input).path("failedPayments");}}
    private MpcNearTransactionCodec.Unsigned unsigned(JsonNode item){return json.treeToValue(item.path("unsigned"),MpcNearTransactionCodec.Unsigned.class);}
    private MpcNearTransactionCodec.Signed selected(JsonNode item){return json.treeToValue(item.path("selected"),MpcNearTransactionCodec.Signed.class);}
    private ObjectNode copy(JsonNode item){return (ObjectNode)json.readTree(item.path("receipt").toString());}
    @Test void actualThreeQuorumsAndAllAssetsProveExactFeesAndRetainedOneYocto()throws Exception {
        assertEquals(9,fixtures().size());
        for(var item:fixtures()) {
            var u=unsigned(item);var result=NearExecutionReceipt.verify(item.path("receipt"),u,selected(item));assertTrue(result.paid());
            assertEquals(new BigInteger(item.path("feeYocto").asText()),result.networkFeeYocto());
            assertEquals(result.networkFeeYocto().add(u.tokenContract()==null?BigInteger.ZERO:BigInteger.ONE),result.expenseYocto());
            assertEquals(new BigInteger(item.path("sourceBefore").asText()).subtract(new BigInteger(item.path("sourceAfter").asText())).subtract(u.tokenContract()==null?u.amountAtomic():BigInteger.ZERO),result.expenseYocto());
            assertTrue(result.blockHashes().size()>=1);assertTrue(result.gasBurnt()>0);
        }
    }
    @Test void signatureNoncePrincipalOrAnotherActionCannotSettle()throws Exception {
        var item=fixtures().get(0);var original=copy(item);
        for(String field:List.of("signature","public_key","signer_id","receiver_id")) {
            var changed=copy(item);((ObjectNode)changed.path("transaction")).put(field,"changed");
            assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(changed,unsigned(item),selected(item)));
        }
        var nonce=copy(item);((ObjectNode)nonce.path("transaction")).put("nonce",1);
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(nonce,unsigned(item),selected(item)));
        var amount=copy(item);((ObjectNode)amount.path("transaction").path("actions").get(0).path("Transfer")).put("deposit","1");
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(amount,unsigned(item),selected(item)));
        assertTrue(NearExecutionReceipt.verify(original,unsigned(item),selected(item)).paid());
    }
    @Test void incompleteOrDuplicatedRefundClosureCannotSettle()throws Exception {
        var item=fixtures().get(0);var missing=copy(item);missing.withArray("receipts_outcome").remove(1);
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(missing,unsigned(item),selected(item)));
        var duplicate=copy(item);duplicate.withArray("receipts_outcome").add(duplicate.path("receipts_outcome").get(1));
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(duplicate,unsigned(item),selected(item)));
        var wrong=copy(item);((ObjectNode)wrong.path("receipts_outcome").get(1).path("outcome")).put("executor_id","other.near");
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(wrong,unsigned(item),selected(item)));
    }
    @Test void nonFinalOrAsynchronousPrimaryExecutionRequiresReconciliation()throws Exception {
        var item=fixtures().get(3);var pending=copy(item);pending.put("final_execution_status","EXECUTED");
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(pending,unsigned(item),selected(item)));
        var promise=copy(item);var status=json.createObjectNode().put("SuccessReceiptId",promise.path("receipts_outcome").get(1).path("id").asText());
        ((ObjectNode)promise.path("receipts_outcome").get(0).path("outcome")).set("status",status);promise.set("status",status);
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(promise,unsigned(item),selected(item)));
    }
    @Test void tokenContractArgumentsAndTransferEventRemainExact()throws Exception {
        var item=fixtures().get(3);assertNotNull(unsigned(item).tokenContract());
        for(String field:List.of("old_owner_id","new_owner_id","amount")) {
            var changed=copy(item);var primary=(ObjectNode)changed.path("receipts_outcome").get(0).path("outcome");var event=json.readTree(primary.path("logs").get(0).asText().substring("EVENT_JSON:".length()).trim());
            ((ObjectNode)event.path("data").get(0)).put(field,field.equals("amount")?"1":"other.near");primary.putArray("logs").add("EVENT_JSON:"+event);
            assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(changed,unsigned(item),selected(item)));
        }
        var deposit=copy(item);((ObjectNode)deposit.path("transaction").path("actions").get(0).path("FunctionCall")).put("deposit","2");
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(deposit,unsigned(item),selected(item)));
    }
    @Test void missingAndDuplicateTokenEventsCannotCompleteThePayment()throws Exception {
        var item=fixtures().get(3);var missing=copy(item);((ObjectNode)missing.path("receipts_outcome").get(0).path("outcome")).putArray("logs");
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(missing,unsigned(item),selected(item)));
        var duplicate=copy(item);var logs=(tools.jackson.databind.node.ArrayNode)duplicate.path("receipts_outcome").get(0).path("outcome").path("logs");logs.add(logs.get(0));
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(duplicate,unsigned(item),selected(item)));
    }
    @Test void actualFailedNativeTokenAndOutOfGasActionsRestoreDepositsAndChargeOnlyOutcomeFees()throws Exception {
        assertEquals(3,failed().size());
        for(var item:failed()) {
            var result=NearExecutionReceipt.verify(item.path("receipt"),unsigned(item),selected(item));assertFalse(result.paid());
            assertEquals(result.networkFeeYocto(),result.expenseYocto());
            assertEquals(new BigInteger(item.path("sourceBefore").asText()).subtract(new BigInteger(item.path("sourceAfter").asText())),result.expenseYocto());
            assertEquals(new BigInteger(item.path("feeYocto").asText()),result.networkFeeYocto());
        }
    }
    @Test void failedActionCannotRefundWithoutCompleteOwnedNativeRestoration()throws Exception {
        for(var item:failed()) {
            var missing=copy(item);missing.withArray("receipts_outcome").remove(2);
            assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(missing,unsigned(item),selected(item)));
            var wrong=copy(item);((ObjectNode)wrong.path("receipts_outcome").get(1).path("outcome")).put("executor_id","another.near");
            assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(wrong,unsigned(item),selected(item)));
        }
    }
    @Test void cachedFinalProofRequiresEveryExactBlockHeaderAtOrAfterTheSigningReference()throws Exception {
        var item=fixtures().get(3);var u=unsigned(item);var signed=selected(item);var raw=item.path("receipt");
        var outcome=NearExecutionReceipt.verify(raw,u,signed);var headers=new HashMap<String,JsonNode>();
        for(String hash:outcome.blockHashes())headers.put(hash,json.createObjectNode().put("hash",hash).put("height",u.blockHeight()+10));
        assertEquals(u.blockHeight()+10,NearExecutionReceipt.verify(new NearExecutionReceipt.Proof(raw,headers),u,signed).blockHeight());
        var missing=new HashMap<>(headers);missing.remove(outcome.blockHashes().iterator().next());
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(new NearExecutionReceipt.Proof(raw,missing),u,signed));
        String first=outcome.blockHashes().iterator().next();var wrong=new HashMap<>(headers);wrong.put(first,json.createObjectNode().put("hash",first).put("height",u.blockHeight()-1));
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(new NearExecutionReceipt.Proof(raw,wrong),u,signed));
        wrong.put(first,json.createObjectNode().put("hash","another").put("height",u.blockHeight()+10));
        assertThrows(RuntimeException.class,()->NearExecutionReceipt.verify(new NearExecutionReceipt.Proof(raw,wrong),u,signed));
    }
}

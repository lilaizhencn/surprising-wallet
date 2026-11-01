package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public receipt and actual MPC witness retained from an isolated official Aptos localnet. */
class AptosExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode receipt() throws Exception {try(var input=getClass().getResourceAsStream("/mpc/aptos-mpc-transfer-receipt.json")){assertNotNull(input);return (ObjectNode)json.readTree(input);}}
    private MpcAptosTransactionCodec.Unsigned unsigned(JsonNode r) {
        boolean fa=r.path("payload").path("function").asText().contains("primary_fungible_store");var args=r.path("payload").path("arguments");
        return new MpcAptosTransactionCodec.Unsigned(r.path("sender").asText(),r.path("sequence_number").asLong(),args.path(fa?1:0).asText(),args.path(fa?2:1).asLong(),fa?args.path(0).path("inner").asText():null,
                r.path("max_gas_amount").asLong(),r.path("gas_unit_price").asLong(),r.path("expiration_timestamp_secs").asLong(),4,r.path("signature").path("public_key").asText().substring(2));
    }
    private MpcAptosTransactionCodec.Signed selected(JsonNode r) {return MpcAptosTransactionCodec.assemble(unsigned(r),HexFormat.of().parseHex(r.path("signature").path("signature").asText().substring(2)));}
    @Test void actualMpcReceiptBindsHashSourceRecipientAndFee() throws Exception {
        var r=receipt();var outcome=AptosExecutionReceipt.verify(r,unsigned(r),selected(r));assertTrue(outcome.paid());assertEquals(63,outcome.gasUsed());assertEquals(6300,outcome.feeOctas());
    }
    @Test void actualMpcFaReceiptUsesExactAbiMetadataObjectAndRejectsExtraFields() throws Exception {
        ObjectNode r;try(var input=getClass().getResourceAsStream("/mpc/aptos-mpc-fa-transfer-receipt.json")){assertNotNull(input);r=(ObjectNode)json.readTree(input);}
        var u=unsigned(r);var signed=selected(r);assertTrue(AptosExecutionReceipt.verify(r,u,signed).paid());
        ((ObjectNode)r.path("payload").path("arguments").path(0)).put("extra","forbidden");
        assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,signed));
    }
    @Test void actualMpcRecipientWithLeadingZeroUsesTheSameThirtyTwoByteAddress() throws Exception {
        ObjectNode r;try(var input=getClass().getResourceAsStream("/mpc/aptos-mpc-short-address-receipt.json")){assertNotNull(input);r=(ObjectNode)json.readTree(input);}
        assertEquals(65,r.path("payload").path("arguments").path(0).asText().length());
        assertTrue(AptosExecutionReceipt.verify(r,unsigned(r),selected(r)).paid());
    }
    @Test void shortAbiMetadataAndRecipientAreComparedByExactAddressBytes() throws Exception {
        JsonNode v;try(var input=getClass().getResourceAsStream("/mpc/aptos-codec-reference.json")){v=json.readTree(input).path("vectors").path(1);}
        var u=new MpcAptosTransactionCodec.Unsigned(v.path("sender").asText(),v.path("sequence").asLong(),v.path("recipient").asText(),v.path("amountAtomic").asLong(),v.path("metadata").asText(),v.path("maxGasAmount").asLong(),v.path("gasUnitPrice").asLong(),v.path("expiration").asLong(),v.path("chainId").asInt(),v.path("publicKeyHex").asText());
        var signed=MpcAptosTransactionCodec.assemble(u,HexFormat.of().parseHex(v.path("signatureHex").asText()));
        var r=com.surprising.wallet.testutil.AptosReceiptTestSupport.receipt(u,signed,false,0);
        ((ObjectNode)r.path("payload").path("arguments").path(0)).put("inner","0xcafe");
        ((ArrayNode)r.path("payload").path("arguments")).set(1,StringNode.valueOf("0x12345"));
        assertTrue(AptosExecutionReceipt.verify(r,u,signed).paid());
        ((ArrayNode)r.path("payload").path("arguments")).set(1,StringNode.valueOf("0x12346"));
        assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,signed));
    }
    @Test void mismatchedHashSequenceWitnessOrPaymentEventCannotConfirm() throws Exception {
        for(String field:java.util.List.of("hash","sequence_number","witness","amount","store","pending")) {
            var r=receipt();var u=unsigned(r);var s=selected(r);
            switch(field){case "hash"->r.put("hash","0x"+"00".repeat(32));case "sequence_number"->r.put("sequence_number","100");
                case "witness"->((ObjectNode)r.path("signature")).put("signature","0x"+"00".repeat(64));
                case "amount"->((ObjectNode)r.path("events").path(1).path("data")).put("amount","12344");
                case "store"->((ObjectNode)r.path("events").path(1).path("data")).put("store","0x1");case "pending"->r.put("type","pending_transaction");}
            assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,s),field);
        }
    }
    @Test void confirmedFailureRequiresCommittedOutcomeAndNoPaymentEffects() throws Exception {
        var r=receipt();var u=unsigned(r);var s=selected(r);r.put("success",false).put("vm_status","Move abort: insufficient balance");
        assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,s));var events=(ArrayNode)r.path("events");events.remove(1);events.remove(0);
        assertFalse(AptosExecutionReceipt.verify(r,u,s).paid());
    }
    @Test void storageRefundReducesActualCostAndDuplicateOrMismatchedFeeStatementsFail() throws Exception {
        var r=receipt();var u=unsigned(r);var s=selected(r);var fee=(ObjectNode)r.path("events").path(2).path("data");fee.put("storage_fee_refund_octas","123");assertEquals(6177,AptosExecutionReceipt.verify(r,u,s).feeOctas());
        fee.put("total_charge_gas_units","62");assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,s));fee.put("total_charge_gas_units","63");
        ((ArrayNode)r.path("events")).add(r.path("events").path(2).deepCopy());assertThrows(RuntimeException.class,()->AptosExecutionReceipt.verify(r,u,s));
    }
}

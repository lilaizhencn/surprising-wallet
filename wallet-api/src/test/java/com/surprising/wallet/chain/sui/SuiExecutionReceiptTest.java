package com.surprising.wallet.chain.sui;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;

import com.google.protobuf.ByteString;
import com.surprising.wallet.testutil.SuiReceiptTestSupport;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public original protobuf captured from actual three-quorum MPC transfers, including gas smashing rebates. */
class SuiExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixtures() throws Exception {
        try(var in=getClass().getResourceAsStream("/mpc/sui-actual-native-receipts.json")){return json.readTree(in).path("payments");}
    }
    private MpcSuiTransactionCodec.Unsigned unsigned(JsonNode item){return json.treeToValue(item.path("unsigned"),MpcSuiTransactionCodec.Unsigned.class);}
    private MpcSuiTransactionCodec.Signed signed(JsonNode item){return json.treeToValue(item.path("selected"),MpcSuiTransactionCodec.Signed.class);}
    @Test void actualThreeQuorumsProveExactPrincipalAndSignedNetStorageExpense()throws Exception {
        var fixtures=fixtures();assertEquals(3,fixtures.size());
        for(var item:fixtures){var result=SuiExecutionReceipt.verify(item.path("receipt"),unsigned(item),signed(item));assertTrue(result.paid());assertTrue(result.checkpoint()>0);assertEquals(item.path("feeMist").asLong(),result.feeMist());}
        assertTrue(fixtures.get(0).path("feeMist").asLong()<0);
    }
    @Test void untrustedConvenienceJsonCannotOverrideOriginalSuccessAmountOrFee()throws Exception {
        var item=fixtures().get(0);var receipt=json.createObjectNode().put("executedTransactionBase64",item.path("receipt").path("executedTransactionBase64").asText()).put("checkpoint",999);
        receipt.putObject("effects").putObject("status").put("status","failure");receipt.putArray("balanceChanges");
        assertTrue(SuiExecutionReceipt.verify(receipt,unsigned(item),signed(item)).paid());
        assertEquals(item.path("feeMist").asLong(),SuiExecutionReceipt.verify(receipt,unsigned(item),signed(item)).feeMist());
    }
    @Test void anotherBcsOrWitnessCannotCompleteTheOwnedPayment()throws Exception {
        var item=fixtures().get(1);var tx=SuiExecutionReceipt.decode(item.path("receipt"));
        byte[] bytes=tx.getTransaction().getBcs().getValue().toByteArray();bytes[bytes.length-1]^=1;
        var changed=tx.toBuilder().setTransaction(tx.getTransaction().toBuilder().setBcs(tx.getTransaction().getBcs().toBuilder().setValue(ByteString.copyFrom(bytes)))).build();
        assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changed),unsigned(item),signed(item)));
        byte[] witness=tx.getSignatures(0).getBcs().getValue().toByteArray();witness[witness.length-1]^=1;
        var changedWitness=tx.toBuilder().setSignatures(0,tx.getSignatures(0).toBuilder().setBcs(tx.getSignatures(0).getBcs().toBuilder().setValue(ByteString.copyFrom(witness)))).build();
        assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changedWitness),unsigned(item),signed(item)));
    }
    @Test void missingFinalityOrDifferentEffectIdentityCannotSettle()throws Exception {
        var item=fixtures().get(1);var tx=SuiExecutionReceipt.decode(item.path("receipt"));
        for(var changed:java.util.List.of(tx.toBuilder().clearCheckpoint().build(),tx.toBuilder().setEffects(tx.getEffects().toBuilder().setTransactionDigest(signed(fixtures().get(2)).digest())).build()))
            assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changed),unsigned(item),signed(item)));
    }
    @Test void changedRecipientPrincipalOrDuplicateChangesCannotSettle()throws Exception {
        var item=fixtures().get(1);var tx=SuiExecutionReceipt.decode(item.path("receipt"));
        for(var changed:java.util.List.of(tx.toBuilder().setBalanceChanges(0,tx.getBalanceChanges(0).toBuilder().setAmount("-1")).build(),
                tx.toBuilder().addBalanceChanges(tx.getBalanceChanges(0)).build(),
                tx.toBuilder().setBalanceChanges(1,tx.getBalanceChanges(1).toBuilder().setAddress("0x1")).build()))
            assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changed),unsigned(item),signed(item)));
    }
    @Test void gasChangesAndFailureFlagsMustMatchActualBalanceEffects()throws Exception {
        var item=fixtures().get(1);var tx=SuiExecutionReceipt.decode(item.path("receipt"));
        var gas=tx.toBuilder().setEffects(tx.getEffects().toBuilder().setGasUsed(tx.getEffects().getGasUsed().toBuilder().setStorageRebate(0))).build();
        var failed=tx.toBuilder().setEffects(tx.getEffects().toBuilder().setStatus(tx.getEffects().getStatus().toBuilder().setSuccess(false))).build();
        for(var changed:java.util.List.of(gas,failed))assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changed),unsigned(item),signed(item)));
    }
    @Test void actualOutOfGasProofConsumesOnlyGasAndCannotBecomeSuccessfulPayment()throws Exception {
        JsonNode item;try(var in=getClass().getResourceAsStream("/mpc/sui-actual-failed-receipt.json")){item=json.readTree(in);}
        var proof=item.path("receipt");var result=SuiExecutionReceipt.verify(proof,unsigned(item),signed(item));
        assertFalse(result.paid());assertEquals(1_000_000,result.feeMist());
        var tx=SuiExecutionReceipt.decode(proof);var changed=tx.toBuilder().setEffects(tx.getEffects().toBuilder().setStatus(tx.getEffects().getStatus().toBuilder().setSuccess(true))).build();
        assertThrows(RuntimeException.class,()->SuiExecutionReceipt.verify(SuiReceiptTestSupport.envelope(changed),unsigned(item),signed(item)));
    }
}

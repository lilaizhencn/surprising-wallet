package com.surprising.wallet.chain.polkadot;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public original headers/wire/events from twelve actual three-node MPC payments. */
class PolkadotExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixtures()throws Exception{try(var input=getClass().getResourceAsStream("/mpc/polkadot-actual-payment-proofs.json")){return json.readTree(input).path("payments");}}
    private MpcPolkadotTransactionCodec.Unsigned unsigned(JsonNode item){return json.treeToValue(item.path("unsigned"),MpcPolkadotTransactionCodec.Unsigned.class);}
    private MpcPolkadotTransactionCodec.Signed selected(JsonNode item){return json.treeToValue(item.path("selected"),MpcPolkadotTransactionCodec.Signed.class);}
    private ObjectNode copy(JsonNode item){return (ObjectNode)json.readTree(item.path("proof").toString());}
    @Test void allQuorumsAndRolesProveExactPrincipalAndPhysicalNativeFee()throws Exception{
        assertEquals(12,fixtures().size());
        for(var item:fixtures()) {
            var u=unsigned(item);var result=PolkadotExecutionReceipt.verify(item.path("proof"),u,selected(item));assertTrue(result.paid());
            assertEquals(new BigInteger(item.path("feePlanck").asText()),result.feePlanck());
            assertEquals(new BigInteger(item.path("nativeBefore").asText()).subtract(new BigInteger(item.path("nativeAfter").asText())).subtract(u.assetId()==null?u.amountAtomic():BigInteger.ZERO),result.feePlanck());
        }
    }
    @Test void selectedWireNetworkRuntimeAndHeaderCannotBeReplaced()throws Exception{
        var item=fixtures().get(0);
        for(String field:List.of("extrinsicHex","txHash","blockHash","headerHex","genesisHash")){
            var proof=copy(item);proof.put(field,"0x"+"00".repeat(32));assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(proof,unsigned(item),selected(item)));
        }
        for(String field:List.of("specVersion","transactionVersion","blockHeight","finalizedHeight","extrinsicIndex")){
            var proof=copy(item);proof.put(field,-1);assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(proof,unsigned(item),selected(item)));
        }
    }
    @Test void anotherPayerPrincipalOrPhaseAndNonzeroTipCannotSettle()throws Exception{
        var item=fixtures().get(0);
        for(int field:List.of(0,1,2)) {
            var proof=copy(item);var event=event(proof,"TransactionFeePaid");((ArrayNode)event.path("data")).set(field,json.getNodeFactory().textNode(field==0?unsigned(item).recipient():field==1?"-1":"1"));
            assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(proof,unsigned(item),selected(item)));
        }
        var principal=copy(item);((ArrayNode)event(principal,"Transfer").path("data")).set(2,json.getNodeFactory().textNode("1"));
        assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(principal,unsigned(item),selected(item)));
        var phase=copy(item);event(phase,"Transfer").put("index",phase.path("extrinsicIndex").asInt()+1);
        assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(phase,unsigned(item),selected(item)));
    }
    @Test void missingOrDuplicatedFeeDispatchAndTransferAreAmbiguous()throws Exception{
        var item=fixtures().get(0);
        for(String method:List.of("TransactionFeePaid","ExtrinsicSuccess","Transfer")){
            var duplicate=copy(item);duplicate.withArray("events").add(event(duplicate,method));
            assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(duplicate,unsigned(item),selected(item)));
            var missing=copy(item);var events=missing.withArray("events");for(int i=events.size()-1;i>=0;i--)if(events.get(i).path("method").asText().equals(method))events.remove(i);
            assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(missing,unsigned(item),selected(item)));
        }
    }
    @Test void actualWholeAssetCollectionsRetainNativeNonceAndActualCommittedFailuresRetainFees()throws Exception {
        for(String kind:List.of("collection","failed")) {
            JsonNode items;try(var input=getClass().getResourceAsStream("/mpc/polkadot-actual-"+kind+"-proofs.json")){items=json.readTree(input).path("payments");}
            assertEquals(kind.equals("collection")?3:2,items.size());for(var item:items){var u=unsigned(item);var result=PolkadotExecutionReceipt.verify(item.path("proof"),u,selected(item));assertEquals(kind.equals("collection"),result.paid());assertTrue(result.feePlanck().signum()>0);if(kind.equals("collection") && u.assetId()!=null)assertFalse(u.keepAlive());}
        }
    }
    @Test void killingThePublishedNativeAccountCannotBeAcceptedAsASafeAssetPayment()throws Exception {
        var item=fixtures().get(0);var proof=copy(item);proof.withArray("events").addObject().put("section","system").put("method","KilledAccount").putArray("data").add(unsigned(item).sender());
        assertThrows(RuntimeException.class,()->PolkadotExecutionReceipt.verify(proof,unsigned(item),selected(item)));
    }
    private ObjectNode event(JsonNode proof,String method){for(var event:proof.path("events"))if(method.equals(event.path("method").asText()))return (ObjectNode)event;throw new AssertionError(method);}
}

package com.surprising.wallet.chain.aptos;

import java.math.BigInteger;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Selected BCS/hash/witness, committed outcome, exact primary-store payment and actual net fee. */
public final class AptosExecutionReceipt {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final BigInteger U64_MAX=BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private AptosExecutionReceipt() { }
    public record Result(boolean paid,long version,long gasUsed,long gasUnitPrice,long feeOctas) { }
    public static Result verify(JsonNode receipt,MpcAptosTransactionCodec.Unsigned unsigned,MpcAptosTransactionCodec.Signed selected) {
        require(receipt!=null && "user_transaction".equals(receipt.path("type").asText()) && receipt.path("success").isBoolean(),"Aptos receipt is not a committed user execution");
        var selectedJson=JSON.readTree(selected.json());String signature=selectedJson.path("signature").path("signature").asText();
        require(signature.matches("0x[0-9a-f]{128}") && selected.equals(MpcAptosTransactionCodec.assemble(unsigned,HexFormat.of().parseHex(signature.substring(2)))),"Aptos selected raw/hash differs from verified fixed message");
        require(selected.txId().equals(receipt.path("hash").asText()) && selectedJson.path("signature").equals(receipt.path("signature")),"Aptos receipt differs from selected transaction hash or witness");
        var expected=MpcAptosTransactionCodec.unsignedJson(unsigned);
        // REST accepts an address for Object<Metadata>, then returns the ABI-decoded struct.
        // Compare the exact canonical one-field object, including rejection of extra fields.
        if(unsigned.metadata()!=null)((tools.jackson.databind.node.ArrayNode)expected.path("payload").path("arguments"))
                .set(0,JSON.createObjectNode().put("inner",unsigned.metadata()));
        var actualPayload=receipt.path("payload").deepCopy();
        require(actualPayload.isObject() && actualPayload.path("arguments").isArray(),"Aptos ABI payment payload is absent");
        var actualArgs=(tools.jackson.databind.node.ArrayNode)actualPayload.path("arguments");
        int recipientIndex=unsigned.metadata()==null?0:1;
        require(actualArgs.size()==recipientIndex+2 && actualArgs.path(recipientIndex).isTextual(),"Aptos ABI arguments differ");
        actualArgs.set(recipientIndex,tools.jackson.databind.node.StringNode.valueOf(MpcAptosTransactionCodec.canonicalAddress(actualArgs.path(recipientIndex).asText())));
        if(unsigned.metadata()!=null) {
            var metadata=actualArgs.path(0);
            require(metadata.isObject() && metadata.size()==1 && metadata.path("inner").isTextual(),"Aptos metadata is not the exact ABI object");
            actualArgs.set(0,JSON.createObjectNode().put("inner",MpcAptosTransactionCodec.canonicalAddress(metadata.path("inner").asText())));
        }
        for(var field:expected.properties()) {
            JsonNode actual=field.getKey().equals("payload")?actualPayload:receipt.path(field.getKey());
            if(field.getKey().equals("sender"))actual=tools.jackson.databind.node.StringNode.valueOf(MpcAptosTransactionCodec.canonicalAddress(actual.asText()));
            require(field.getValue().equals(actual),"Aptos committed payment field differs: "+field.getKey());
        }
        long version=atomic(receipt.path("version")).longValueExact(),used=atomic(receipt.path("gas_used")).longValueExact();
        require(used<=unsigned.maxGasAmount(),"Aptos gas used exceeds fixed maximum");
        var events=receipt.path("events");require(events.isArray(),"Aptos execution events are absent");
        BigInteger refund=null;BigInteger withdrawn=BigInteger.ZERO,deposited=BigInteger.ZERO;
        String metadata=unsigned.metadata()==null?"0xa":unsigned.metadata();String sourceStore=MpcAptosTransactionCodec.primaryStore(unsigned.sender(),metadata),targetStore=MpcAptosTransactionCodec.primaryStore(unsigned.recipient(),metadata);
        for(var event:events) {
            String type=event.path("type").asText();var data=event.path("data");
            if(type.equals("0x1::transaction_fee::FeeStatement")) {
                require(refund==null && atomic(data.path("total_charge_gas_units")).longValueExact()==used,"Aptos fee statement differs from gas used");refund=atomic(data.path("storage_fee_refund_octas"));
            }
            if(type.equals("0x1::fungible_asset::Withdraw") || type.equals("0x1::fungible_asset::Deposit")) {
                require(receipt.path("success").asBoolean(),"Aptos failed execution emitted a payment");
                String store=MpcAptosTransactionCodec.canonicalAddress(data.path("store").asText());
                if(type.endsWith("::Withdraw") && store.equals(sourceStore))withdrawn=withdrawn.add(atomic(data.path("amount")));
                if(type.endsWith("::Deposit") && store.equals(targetStore))deposited=deposited.add(atomic(data.path("amount")));
            }
        }
        require(refund!=null,"Aptos actual fee statement is absent");BigInteger fee=BigInteger.valueOf(used).multiply(BigInteger.valueOf(unsigned.gasUnitPrice())).subtract(refund);
        require(fee.signum()>=0,"Aptos refund exceeds charged network fee; separate reconciliation is required");
        boolean paid=receipt.path("success").asBoolean();
        if(paid)require(withdrawn.equals(BigInteger.valueOf(unsigned.amountAtomic())) && deposited.equals(withdrawn),"Aptos primary stores do not prove exact source and recipient amounts");
        else require(!receipt.path("vm_status").asText().isBlank(),"Aptos failed execution has no committed VM outcome");
        return new Result(paid,version,used,unsigned.gasUnitPrice(),fee.longValueExact());
    }
    private static BigInteger atomic(JsonNode node) {String text=node.asText();require((node.isTextual()||node.isIntegralNumber()) && text.matches("[0-9]+"),"Aptos chain amount is not an integer");var value=new BigInteger(text);require(value.compareTo(U64_MAX)<=0,"Aptos chain amount exceeds u64");return value;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

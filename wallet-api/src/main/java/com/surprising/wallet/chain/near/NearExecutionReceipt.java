package com.surprising.wallet.chain.near;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import java.math.BigInteger;
import java.util.*;
import org.bitcoinj.base.Base58;
import tools.jackson.databind.*;

/** FINAL single-action payment and complete system-refund outcomes, bound to the externally signed Borsh. */
public final class NearExecutionReceipt {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NearExecutionReceipt() { }
    public record Outcome(boolean paid,long gasBurnt,BigInteger networkFeeYocto,BigInteger expenseYocto,Set<String> blockHashes) {
        public Outcome {blockHashes=Set.copyOf(blockHashes);}
    }
    public record Proof(JsonNode execution,Map<String,JsonNode> headers) {
        public Proof {Objects.requireNonNull(execution);headers=Map.copyOf(headers);}
    }
    public record Finalized(Outcome outcome,long blockHeight) { }
    public static Finalized verify(Proof proof,MpcNearTransactionCodec.Unsigned u,MpcNearTransactionCodec.Signed signed) {
        var outcome=verify(proof.execution(),u,signed);
        require(proof.headers().keySet().equals(outcome.blockHashes()),"NEAR finalized proof headers do not cover the exact receipt closure");
        long height=0;
        for(var entry:proof.headers().entrySet()) {
            var header=entry.getValue();long blockHeight=integer(header.path("height")).longValueExact();
            require(entry.getKey().equals(header.path("hash").asText()) && blockHeight>=u.blockHeight(),"NEAR receipt header identity or reference height differs");
            height=Math.max(height,blockHeight);
        }
        require(height>0,"NEAR finalized receipt height is absent");return new Finalized(outcome,height);
    }
    public static Outcome verify(JsonNode receipt,MpcNearTransactionCodec.Unsigned u,MpcNearTransactionCodec.Signed signed) {
        MpcNearTransactionCodec.verifySelected(u,signed);
        require(receipt!=null && receipt.isObject() && "FINAL".equals(receipt.path("final_execution_status").asText()),"NEAR execution is not final");
        var tx=receipt.path("transaction");
        require(signed.txHash().equals(tx.path("hash").asText()) && u.sender().equals(tx.path("signer_id").asText())
                && (u.tokenContract()==null?u.recipient():u.tokenContract()).equals(tx.path("receiver_id").asText())
                && integer(tx.path("nonce")).equals(BigInteger.valueOf(u.nonce())) && MpcNearTransactionCodec.publicKey(u).equals(tx.path("public_key").asText()),"NEAR transaction identity, nonce or joint key differs");
        require(integer(tx.path("priority_fee")).signum()==0 && !tx.hasNonNull("nonce_index") && !tx.hasNonNull("nonce_mode"),"NEAR selected ordinary access-key transaction changed its fee or nonce mode");
        byte[] raw=Base64.getDecoder().decode(signed.signedTransactionBase64());
        require(("ed25519:"+Base58.encode(Arrays.copyOfRange(raw,raw.length-64,raw.length))).equals(tx.path("signature").asText()),"NEAR original execution witness differs");
        var actions=tx.path("actions");require(actions.isArray() && actions.size()==1 && actions.get(0).isObject() && actions.get(0).size()==1,"NEAR fixed payment contains another action");
        if(u.tokenContract()==null) {
            var transfer=actions.get(0).path("Transfer");require(transfer.isObject() && transfer.size()==1 && integer(transfer.path("deposit")).equals(u.amountAtomic()),"NEAR native principal differs");
        } else {
            var call=actions.get(0).path("FunctionCall");require(call.isObject() && call.size()==4 && "ft_transfer".equals(call.path("method_name").asText())
                    && integer(call.path("gas")).equals(BigInteger.valueOf(u.gas())) && integer(call.path("deposit")).equals(BigInteger.ONE)
                    && Arrays.equals(Base64.getDecoder().decode(call.path("args").asText()),MpcNearTransactionCodec.arguments(u)),"NEP-141 contract action, exact arguments, gas or one-yocto deposit differs");
        }
        var transaction=receipt.path("transaction_outcome");
        require(signed.txHash().equals(transaction.path("id").asText()) && u.sender().equals(transaction.path("outcome").path("executor_id").asText()),"NEAR transaction outcome differs from owned sender");
        var rootIds=ids(transaction);require(rootIds.size()==1,"NEAR payment transaction did not produce one action receipt");String primaryId=rootIds.getFirst();
        var rootStatus=transaction.path("outcome").path("status");require(rootStatus.isObject() && rootStatus.size()==1 && primaryId.equals(rootStatus.path("SuccessReceiptId").asText()),"NEAR action receipt is not the signed transaction result");
        var list=receipt.path("receipts_outcome");require(list.isArray() && list.size()>=1 && list.size()<=3,"NEAR payment receipt closure is absent or contains additional actions");
        var outcomes=new LinkedHashMap<String,JsonNode>();var blocks=new HashSet<String>();blocks.add(blockHash(transaction));
        BigInteger fee=integer(transaction.path("outcome").path("tokens_burnt"));long gas=integer(transaction.path("outcome").path("gas_burnt")).longValueExact();
        for(var item:list) {
            String id=item.path("id").asText();digest(id);require(!id.equals(signed.txHash()) && outcomes.putIfAbsent(id,item)==null,"NEAR duplicate or cyclic receipt identity");
            blocks.add(blockHash(item));fee=fee.add(integer(item.path("outcome").path("tokens_burnt")));gas=Math.addExact(gas,integer(item.path("outcome").path("gas_burnt")).longValueExact());
        }
        var primary=Objects.requireNonNull(outcomes.get(primaryId),"NEAR primary action receipt is absent");
        require((u.tokenContract()==null?u.recipient():u.tokenContract()).equals(primary.path("outcome").path("executor_id").asText()),"NEAR primary action executed on another receiver");
        var refundIds=ids(primary);require(outcomes.size()==refundIds.size()+1 && !refundIds.contains(primaryId),"NEAR execution includes unresolved or unrelated actions");
        for(String id:refundIds) {
            var refund=Objects.requireNonNull(outcomes.get(id),"NEAR system refund receipt is absent");var status=refund.path("outcome").path("status");
            require(u.sender().equals(refund.path("outcome").path("executor_id").asText()) && ids(refund).isEmpty()
                    && status.isObject() && status.size()==1 && status.has("SuccessValue") && status.path("SuccessValue").asText().isEmpty()
                    && refund.path("outcome").path("logs").isArray() && refund.path("outcome").path("logs").isEmpty(),"NEAR refund did not finish on the owned payer");
        }
        var actionStatus=primary.path("outcome").path("status");var status=receipt.path("status");
        require(actionStatus.isObject() && actionStatus.size()==1 && status.equals(actionStatus),"NEAR global result differs from the exact primary action");
        boolean paid=actionStatus.has("SuccessValue");require(paid || actionStatus.has("Failure"),"NEAR payment requires asynchronous or unrecognized action reconciliation");
        if(!paid)require(!refundIds.isEmpty(),"NEAR failed principal or attached deposit has no completed refund");
        if(paid)require(actionStatus.path("SuccessValue").isTextual(),"NEAR successful return value is malformed");
        if(u.tokenContract()!=null) {
            if(paid)verifyTransferEvent(primary,u);
        }
        BigInteger expense=fee.add(paid && u.tokenContract()!=null?BigInteger.ONE:BigInteger.ZERO);
        require(expense.bitLength()<=128,"NEAR network expense exceeds account amount bounds");
        return new Outcome(paid,gas,fee,expense,blocks);
    }
    private static void verifyTransferEvent(JsonNode primary,MpcNearTransactionCodec.Unsigned u) {
        var logs=primary.path("outcome").path("logs");require(logs.isArray() && logs.size()<=128,"NEP-141 logs are absent or oversized");int transfers=0;
        for(var log:logs) {
            require(log.isTextual() && log.asText().length()<=65_536,"NEAR event log is malformed or oversized");String value=log.asText();if(!value.startsWith("EVENT_JSON:"))continue;
            var event=JSON.readTree(value.substring("EVENT_JSON:".length()).trim());if(!"nep141".equals(event.path("standard").asText()))continue;
            require("1.0.0".equals(event.path("version").asText()) && "ft_transfer".equals(event.path("event").asText()),"NEP-141 payment emitted another asset action");
            var data=event.path("data");require(data.isArray() && data.size()==1,"NEP-141 payment emitted multiple principal changes");var transfer=data.get(0);
            require(u.sender().equals(transfer.path("old_owner_id").asText()) && u.recipient().equals(transfer.path("new_owner_id").asText())
                    && u.amountAtomic().equals(integer(transfer.path("amount"))),"NEP-141 exact source, recipient or amount differs");
            if(transfer.hasNonNull("memo"))require(Objects.equals(u.memo(),transfer.path("memo").asText()) || (u.memo()==null && transfer.path("memo").asText().isEmpty()),"NEP-141 event contradicts the signed memo");transfers++;
        }
        require(transfers==1,"NEP-141 exact transfer event is absent or duplicated");
    }
    private static List<String> ids(JsonNode item) {
        var ids=item.path("outcome").path("receipt_ids");require(ids.isArray() && ids.size()<=2,"NEAR payment receipt dependencies are unbounded");var result=new ArrayList<String>();
        for(var id:ids){require(id.isTextual(),"NEAR receipt ID is malformed");String value=id.asText();digest(value);require(!result.contains(value),"NEAR duplicated receipt dependency");result.add(value);}return result;
    }
    private static String blockHash(JsonNode item){String hash=item.path("block_hash").asText();digest(hash);return hash;}
    private static void digest(String value){require(value!=null && value.length()<=44 && Base58.decode(value).length==32,"NEAR execution hash is absent or malformed");}
    private static BigInteger integer(JsonNode value){String text=value.asText();require(text.length()<=39 && text.matches("0|[1-9][0-9]*"),"NEAR unsigned execution amount is absent or malformed");var amount=new BigInteger(text);require(amount.bitLength()<=128,"NEAR execution amount exceeds u128");return amount;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

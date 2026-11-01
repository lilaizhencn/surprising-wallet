package com.surprising.wallet.chain.near;

import java.math.BigInteger;
import java.util.*;
import org.bitcoinj.base.Base58;
import tools.jackson.databind.*;

/** FINAL registration with the exact receipt graph; attached-deposit refunds are distinct from Gas refunds. */
public final class NearStorageReceipt {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NearStorageReceipt() { }
    public record Proof(JsonNode execution,Map<String,JsonNode> headers,Map<String,JsonNode> receipts) {
        public Proof {Objects.requireNonNull(execution);headers=Map.copyOf(headers);receipts=Map.copyOf(receipts);}
    }
    public record Outcome(boolean registered,long blockHeight,long gasBurnt,BigInteger feeYocto,
                          BigInteger returnedDepositYocto,BigInteger expenseYocto) { }

    public static Outcome verify(Proof proof,MpcNearTransactionCodec.Registration u,MpcNearTransactionCodec.Signed signed) {
        MpcNearTransactionCodec.verifySelected(u,signed);
        var execution=proof.execution();require("FINAL".equals(execution.path("final_execution_status").asText()),"NEP-145 execution is not final");
        var tx=execution.path("transaction");
        require(signed.txHash().equals(tx.path("hash").asText()) && u.sender().equals(tx.path("signer_id").asText())
                && u.contract().equals(tx.path("receiver_id").asText()) && number(tx.path("nonce")).equals(BigInteger.valueOf(u.nonce()))
                && MpcNearTransactionCodec.publicKey(u).equals(tx.path("public_key").asText()),"NEP-145 transaction identity differs");
        require(number(tx.path("priority_fee")).signum()==0 && !tx.hasNonNull("nonce_index") && !tx.hasNonNull("nonce_mode"),"NEP-145 fee/nonce mode differs");
        byte[] wire=Base64.getDecoder().decode(signed.signedTransactionBase64());
        require(("ed25519:"+Base58.encode(Arrays.copyOfRange(wire,wire.length-64,wire.length))).equals(tx.path("signature").asText()),"NEP-145 execution witness differs");
        registrationAction(tx.path("actions"),u);
        var root=execution.path("transaction_outcome");
        require(signed.txHash().equals(root.path("id").asText()) && u.sender().equals(root.path("outcome").path("executor_id").asText()),"NEP-145 root outcome differs");
        var roots=children(root);require(roots.size()==1,"NEP-145 root receipt is not unique");String primaryId=roots.getFirst();
        require(primaryId.equals(root.path("outcome").path("status").path("SuccessReceiptId").asText()),"NEP-145 primary is not the root result");
        var outcomes=new LinkedHashMap<String,JsonNode>();var blocks=new HashSet<String>();blocks.add(block(root));
        var list=execution.path("receipts_outcome");require(list.isArray() && list.size()>0 && list.size()<=8,"NEP-145 receipt closure is absent or excessive");
        BigInteger fee=number(root.path("outcome").path("tokens_burnt"));long gas=number(root.path("outcome").path("gas_burnt")).longValueExact();
        for(var item:list) {
            String id=item.path("id").asText();digest(id);
            require(!id.equals(signed.txHash()) && outcomes.putIfAbsent(id,item)==null,"NEP-145 receipt identity is duplicated");
            blocks.add(block(item));fee=fee.add(number(item.path("outcome").path("tokens_burnt")));
            gas=Math.addExact(gas,number(item.path("outcome").path("gas_burnt")).longValueExact());
        }
        require(proof.receipts().keySet().equals(outcomes.keySet()),"NEP-145 full receipts do not cover exact outcomes");
        require(proof.headers().keySet().equals(blocks),"NEP-145 canonical headers do not cover exact execution");
        long height=0;
        for(var entry:proof.headers().entrySet()) {
            var header=entry.getValue();long h=number(header.path("height")).longValueExact();
            require(entry.getKey().equals(header.path("hash").asText()) && h>=u.blockHeight(),"NEP-145 canonical header differs");height=Math.max(height,h);
        }
        var primary=Objects.requireNonNull(outcomes.get(primaryId),"NEP-145 primary outcome is absent");
        var primaryReceipt=proof.receipts().get(primaryId);var primaryAction=action(primaryReceipt,primaryId,u.contract());
        require(u.sender().equals(primaryReceipt.path("predecessor_id").asText()),"NEP-145 predecessor differs");
        signer(primaryAction,u);registrationAction(primaryAction.path("actions"),u);
        var status=primary.path("outcome").path("status");
        require(u.contract().equals(primary.path("outcome").path("executor_id").asText()) && status.isObject()
                && status.size()==1 && status.equals(execution.path("status")),"NEP-145 primary/final result differs");
        boolean registered=status.has("SuccessValue");require(registered || status.has("Failure"),"NEP-145 result is unresolved");
        if(registered) {
            var value=JSON.readTree(Base64.getDecoder().decode(status.path("SuccessValue").asText()));
            BigInteger total=number(value.path("total")),available=number(value.path("available"));
            require(total.signum()>0 && available.compareTo(total)<=0,"NEP-145 storage result is malformed");
        }
        var visited=new HashSet<String>();var pending=new ArrayDeque<String>();pending.add(primaryId);
        BigInteger returned=BigInteger.ZERO;
        while(!pending.isEmpty()) {
            String id=pending.removeFirst();require(visited.add(id),"NEP-145 receipt graph is cyclic or shared");
            var outcome=Objects.requireNonNull(outcomes.get(id),"NEP-145 receipt dependency is absent");
            for(String child:children(outcome))pending.addLast(child);
            if(id.equals(primaryId))continue;
            var full=proof.receipts().get(id);var a=action(full,id,u.sender());
            require(u.sender().equals(outcome.path("outcome").path("executor_id").asText())
                    && outcome.path("outcome").path("status").isObject() && outcome.path("outcome").path("status").size()==1
                    && outcome.path("outcome").path("status").has("SuccessValue") && outcome.path("outcome").path("status").path("SuccessValue").asText().isEmpty()
                    && outcome.path("outcome").path("logs").isArray() && outcome.path("outcome").path("logs").isEmpty(),"NEP-145 refund did not finish on original payer");
            var actions=a.path("actions");require(actions.isArray() && actions.size()==1 && actions.get(0).size()==1
                    && actions.get(0).path("Transfer").isObject(),"NEP-145 refund contains another action");
            BigInteger amount=number(actions.get(0).path("Transfer").path("deposit"));String predecessor=full.path("predecessor_id").asText();
            if(predecessor.equals(u.contract())) {signer(a,u);require(registered,"failed registration generated a contract payment");returned=returned.add(amount);}
            else {
                require(predecessor.equals("system") && children(outcome).isEmpty(),"NEP-145 refund has an unexpected predecessor or dependency");
                require(number(a.path("gas_price")).signum()==0,"NEP-145 system refund Gas price differs");
                if("system".equals(a.path("signer_id").asText())) {
                    require(!registered && ("ed25519:"+Base58.encode(new byte[32])).equals(a.path("signer_public_key").asText()),"NEP-145 balance refund identity differs");returned=returned.add(amount);
                } else signer(a,u); // Gas refund: already reflected by tokens_burnt; never subtract it from attached deposit.
            }
        }
        require(visited.equals(outcomes.keySet()) && returned.compareTo(u.depositAtomic())<=0,"NEP-145 closure or attached refund differs");
        require(registered || returned.equals(u.depositAtomic()),"failed NEP-145 registration did not return the full attached deposit");
        BigInteger expense=fee.add(u.depositAtomic()).subtract(returned);require(expense.bitLength()<=128,"NEP-145 expense exceeds u128");
        return new Outcome(registered,height,gas,fee,returned,expense);
    }
    private static JsonNode action(JsonNode full,String id,String receiver) {
        require(full!=null && id.equals(full.path("receipt_id").asText()) && receiver.equals(full.path("receiver_id").asText())
                && number(full.path("priority")).signum()==0 && full.path("receipt").size()==1,"NEP-145 full receipt identity/type differs");
        var a=full.path("receipt").path("Action");
        require(a.isObject() && a.path("input_data_ids").isArray() && a.path("input_data_ids").isEmpty()
                && a.path("output_data_receivers").isArray() && a.path("output_data_receivers").isEmpty()
                && !a.path("is_promise_yield").asBoolean() && !a.hasNonNull("refund_to"),"NEP-145 receipt is asynchronous or redirects refunds");return a;
    }
    private static void signer(JsonNode a,MpcNearTransactionCodec.Registration u) {
        require(u.sender().equals(a.path("signer_id").asText()) && MpcNearTransactionCodec.publicKey(u).equals(a.path("signer_public_key").asText()),"NEP-145 refund signer differs from original payer");
    }
    private static void registrationAction(JsonNode actions,MpcNearTransactionCodec.Registration u) {
        require(actions.isArray() && actions.size()==1 && actions.get(0).size()==1,"NEP-145 includes another action");var call=actions.get(0).path("FunctionCall");
        require(call.isObject() && call.size()==4 && "storage_deposit".equals(call.path("method_name").asText())
                && number(call.path("gas")).equals(BigInteger.valueOf(u.gas())) && number(call.path("deposit")).equals(u.depositAtomic())
                && Arrays.equals(Base64.getDecoder().decode(call.path("args").asText()),MpcNearTransactionCodec.arguments(u)),"NEP-145 fixed contract action differs");
    }
    private static List<String> children(JsonNode outcome) {
        var ids=outcome.path("outcome").path("receipt_ids");require(ids.isArray() && ids.size()<=4,"NEP-145 dependency list is absent or excessive");var result=new ArrayList<String>();
        for(var id:ids){String value=id.asText();digest(value);require(!result.contains(value),"NEP-145 dependency is duplicated");result.add(value);}return result;
    }
    private static String block(JsonNode outcome){String value=outcome.path("block_hash").asText();digest(value);return value;}
    private static void digest(String value){require(value!=null && value.length()<=44 && Base58.decode(value).length==32,"NEP-145 receipt hash is malformed");}
    private static BigInteger number(JsonNode value){String text=value.asText();require(text.length()<=39 && text.matches("0|[1-9][0-9]*"),"NEP-145 unsigned amount is malformed");var n=new BigInteger(text);require(n.bitLength()<=128,"NEP-145 amount exceeds u128");return n;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

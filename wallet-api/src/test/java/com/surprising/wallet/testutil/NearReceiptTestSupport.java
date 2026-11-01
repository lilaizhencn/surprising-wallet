package com.surprising.wallet.testutil;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import com.surprising.wallet.chain.near.*;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.bitcoinj.base.Base58;

/** Synthetic owned observations in the original actual-node receipt shape; native acceptance uses real RPC proofs. */
public final class NearReceiptTestSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NearReceiptTestSupport() { }
    public static NearExecutionReceipt.Proof payment(MpcNearTransactionCodec.Unsigned u,MpcNearTransactionCodec.Signed signed,boolean paid,long fee) {
        JsonNode fixtures;try(var input=NearReceiptTestSupport.class.getResourceAsStream("/mpc/near-actual-payment-proofs.json")){fixtures=JSON.readTree(input);}catch(Exception failure){throw new IllegalStateException(failure);}
        int index=u.tokenContract()==null?0:(paid?3:1);var receipt=(ObjectNode)fixtures.path(paid?"payments":"failedPayments").get(index).path("receipt").deepCopy();
        var tx=(ObjectNode)receipt.path("transaction");tx.put("hash",signed.txHash()).put("signer_id",u.sender()).put("receiver_id",u.tokenContract()==null?u.recipient():u.tokenContract()).put("nonce",u.nonce()).put("public_key",MpcNearTransactionCodec.publicKey(u));
        byte[] raw=Base64.getDecoder().decode(signed.signedTransactionBase64());tx.put("signature","ed25519:"+Base58.encode(Arrays.copyOfRange(raw,raw.length-64,raw.length)));
        var action=tx.putArray("actions").addObject();
        if(u.tokenContract()==null)action.putObject("Transfer").put("deposit",u.amountAtomic().toString());
        else action.putObject("FunctionCall").put("method_name","ft_transfer").put("gas",u.gas()).put("deposit","1").put("args",Base64.getEncoder().encodeToString(MpcNearTransactionCodec.arguments(u)));
        String block=Base58.encode(new byte[32]);var root=(ObjectNode)receipt.path("transaction_outcome");root.put("id",signed.txHash()).put("block_hash",block);
        ((ObjectNode)root.path("outcome")).put("executor_id",u.sender()).put("tokens_burnt",Long.toString(fee)).put("gas_burnt",1);
        var primary=receipt.path("receipts_outcome").get(0);((ObjectNode)primary.path("outcome")).put("executor_id",u.tokenContract()==null?u.recipient():u.tokenContract());
        for(var item:receipt.path("receipts_outcome")){((ObjectNode)item).put("block_hash",block);var outcome=(ObjectNode)item.path("outcome");outcome.put("tokens_burnt","0").put("gas_burnt",0);outcome.putArray("logs");if(item!=primary)outcome.put("executor_id",u.sender());}
        if(paid && u.tokenContract()!=null) {
            var event=JSON.createObjectNode().put("standard","nep141").put("version","1.0.0").put("event","ft_transfer");
            var data=event.putArray("data").addObject().put("old_owner_id",u.sender()).put("new_owner_id",u.recipient()).put("amount",u.amountAtomic().toString());if(u.memo()!=null)data.put("memo",u.memo());
            ((ObjectNode)primary.path("outcome")).putArray("logs").add("EVENT_JSON:"+event);
        }
        return new NearExecutionReceipt.Proof(receipt,Map.of(block,JSON.createObjectNode().put("hash",block).put("height",123)));
    }

    /** Explicit synthetic storage observations; the native probe separately obtains real full RPC receipts. */
    public static NearStorageReceipt.Proof storage(MpcNearTransactionCodec.Registration u,MpcNearTransactionCodec.Signed signed,
                                                   boolean registered,boolean depositReturned,long fee) {
        String block=Base58.encode(new byte[32]);byte[] idBytes=new byte[32];Arrays.fill(idBytes,(byte)1);String primary=Base58.encode(idBytes);
        Arrays.fill(idBytes,(byte)2);String gas=Base58.encode(idBytes);Arrays.fill(idBytes,(byte)3);String refund=Base58.encode(idBytes);
        var result=JSON.createObjectNode().put("final_execution_status","FINAL");var tx=result.putObject("transaction");
        byte[] wire=Base64.getDecoder().decode(signed.signedTransactionBase64());String key=MpcNearTransactionCodec.publicKey(u);
        tx.put("hash",signed.txHash()).put("signer_id",u.sender()).put("receiver_id",u.contract()).put("nonce",u.nonce()).put("public_key",key).put("priority_fee",0)
                .put("signature","ed25519:"+Base58.encode(Arrays.copyOfRange(wire,wire.length-64,wire.length)));
        tx.putArray("actions").addObject().putObject("FunctionCall").put("method_name","storage_deposit").put("gas",u.gas())
                .put("deposit",u.depositAtomic().toString()).put("args",Base64.getEncoder().encodeToString(MpcNearTransactionCodec.arguments(u)));
        var status=JSON.createObjectNode();
        if(registered)status.put("SuccessValue",Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(JSON.createObjectNode().put("total",u.depositAtomic().toString()).put("available","0"))));
        else status.putObject("Failure").put("error_message","synthetic final storage failure");
        result.set("status",status);var root=JSON.createObjectNode().put("id",signed.txHash()).put("block_hash",block);var rootOutcome=root.putObject("outcome");
        rootOutcome.put("executor_id",u.sender()).put("gas_burnt",1).put("tokens_burnt",Long.toString(fee));rootOutcome.putArray("receipt_ids").add(primary);rootOutcome.putObject("status").put("SuccessReceiptId",primary);result.set("transaction_outcome",root);
        var list=result.putArray("receipts_outcome");var first=list.addObject().put("id",primary).put("block_hash",block);var outcome=first.putObject("outcome");
        outcome.put("executor_id",u.contract()).put("gas_burnt",0).put("tokens_burnt","0");outcome.putArray("logs");outcome.set("status",status);
        var children=outcome.putArray("receipt_ids");children.add(gas);if(depositReturned || !registered)children.add(refund);
        var full=new HashMap<String,JsonNode>();
        var primaryFull=fullReceipt(primary,u.sender(),u.contract(),u.sender(),key);((ObjectNode)primaryFull.path("receipt").path("Action")).set("actions",tx.path("actions").deepCopy());full.put(primary,primaryFull);
        for(String id:depositReturned || !registered?List.of(gas,refund):List.of(gas)) {
            var item=list.addObject().put("id",id).put("block_hash",block);var o=item.putObject("outcome");
            o.put("executor_id",u.sender()).put("gas_burnt",0).put("tokens_burnt","0");o.putArray("logs");o.putArray("receipt_ids");o.putObject("status").put("SuccessValue","");
            boolean balance=id.equals(refund);String predecessor=balance && registered?u.contract():"system";
            String signer=balance && !registered?"system":u.sender(),publicKey=balance && !registered?"ed25519:"+Base58.encode(new byte[32]):key;
            var receipt=fullReceipt(id,predecessor,u.sender(),signer,publicKey);
            ((ObjectNode)receipt.path("receipt").path("Action")).putArray("actions").addObject().putObject("Transfer")
                    .put("deposit",balance?u.depositAtomic().toString():"12345678900000000000000");full.put(id,receipt);
        }
        return new NearStorageReceipt.Proof(result,Map.of(block,JSON.createObjectNode().put("hash",block).put("height",123)),full);
    }
    private static ObjectNode fullReceipt(String id,String predecessor,String receiver,String signer,String key) {
        var receipt=JSON.createObjectNode().put("receipt_id",id).put("predecessor_id",predecessor).put("receiver_id",receiver).put("priority",0);
        var action=receipt.putObject("receipt").putObject("Action");action.put("signer_id",signer).put("signer_public_key",key).put("gas_price","0").put("is_promise_yield",false);
        action.putArray("input_data_ids");action.putArray("output_data_receivers");return receipt;
    }
}

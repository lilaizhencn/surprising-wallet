package com.surprising.wallet.testutil;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic evidence for PG boundary tests only; native probes obtain official node receipts. */
public final class AptosReceiptTestSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AptosReceiptTestSupport() { }
    public static ObjectNode receipt(MpcAptosTransactionCodec.Unsigned u,MpcAptosTransactionCodec.Signed signed,boolean failed,long refund) {
        var r=(ObjectNode)JSON.readTree(signed.json());r.put("type","user_transaction").put("hash",signed.txId()).put("success",!failed).put("vm_status",failed?"Move abort: insufficient balance":"Executed successfully").put("version","123").put("gas_used","100");
        if(u.metadata()!=null)((tools.jackson.databind.node.ArrayNode)r.path("payload").path("arguments")).set(0,JSON.createObjectNode().put("inner",u.metadata()));
        var events=JSON.createArrayNode();String metadata=u.metadata()==null?"0xa":u.metadata();
        if(!failed){events.add(JSON.valueToTree(Map.of("type","0x1::fungible_asset::Withdraw","data",Map.of("store",MpcAptosTransactionCodec.primaryStore(u.sender(),metadata),"amount",Long.toString(u.amountAtomic())))));
            events.add(JSON.valueToTree(Map.of("type","0x1::fungible_asset::Deposit","data",Map.of("store",MpcAptosTransactionCodec.primaryStore(u.recipient(),metadata),"amount",Long.toString(u.amountAtomic())))));}
        events.add(JSON.valueToTree(Map.of("type","0x1::transaction_fee::FeeStatement","data",Map.of("total_charge_gas_units","100","storage_fee_refund_octas",Long.toString(refund)))));r.set("events",events);return r;
    }
}

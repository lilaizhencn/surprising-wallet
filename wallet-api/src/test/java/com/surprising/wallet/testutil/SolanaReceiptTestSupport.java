package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.solana.MpcSolanaTransactionCodec;
import com.surprising.wallet.model.mpc.SolanaSigningPayload;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic evidence for pure/PG boundary tests only. Native probes fetch receipts from Agave. */
public final class SolanaReceiptTestSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SolanaReceiptTestSupport() { }
    public static ObjectNode status(boolean failed) {
        var value=JSON.createObjectNode().put("slot",123L).put("confirmationStatus","confirmed");
        if(failed)value.set("err",JSON.valueToTree(Map.of("InstructionError",List.of(1,Map.of("Custom",1)))));else value.putNull("err");
        return value;
    }
    public static ObjectNode receipt(SolanaSigningPayload payload,MpcSolanaTransactionCodec.Signed signed,boolean failed,boolean newAta) {
        var message=MpcSolanaTransactionCodec.restore(payload.unsigned());
        long[] pre=new long[message.accounts().size()],post=new long[pre.length];
        Arrays.fill(pre,10_000_000_000L);System.arraycopy(pre,0,post,0,pre.length);
        long fee=5000L*message.requiredSignatures(),rent=payload.mint()!=null && newAta?2_039_280:0;
        post[0]-=fee;
        var before=new ArrayList<Map<String,Object>>();var after=new ArrayList<Map<String,Object>>();
        if(!failed) {
            long atomic=payload.amount().movePointRight(payload.decimals()).longValueExact();
            if(payload.mint()==null){post[0]-=atomic;post[index(message,payload.recipient())]+=atomic;}
            else {
                post[0]-=rent;
                int source=index(message,MpcSolanaTransactionCodec.associatedTokenAddress(payload.sender(),payload.mint()));
                int target=index(message,MpcSolanaTransactionCodec.associatedTokenAddress(payload.recipient(),payload.mint()));
                before.add(token(source,payload,payload.sender(),BigInteger.valueOf(atomic).multiply(BigInteger.TEN)));
                if(!newAta)before.add(token(target,payload,payload.recipient(),BigInteger.ZERO));
                after.add(token(source,payload,payload.sender(),BigInteger.valueOf(atomic).multiply(BigInteger.valueOf(9))));
                after.add(token(target,payload,payload.recipient(),BigInteger.valueOf(atomic)));
            }
        }
        var meta=JSON.createObjectNode().put("fee",fee);
        meta.set("err",status(failed).path("err"));meta.set("preBalances",JSON.valueToTree(pre));meta.set("postBalances",JSON.valueToTree(post));
        meta.set("preTokenBalances",JSON.valueToTree(before));meta.set("postTokenBalances",JSON.valueToTree(after));
        var result=JSON.createObjectNode().put("slot",123L);result.set("meta",meta);result.set("transaction",JSON.valueToTree(List.of(signed.transactionBase64(),"base64")));return result;
    }
    private static Map<String,Object> token(int index,SolanaSigningPayload payload,String owner,BigInteger amount) {
        return Map.of("accountIndex",index,"mint",payload.mint(),"owner",owner,
                "uiTokenAmount",Map.of("decimals",payload.decimals(),"amount",amount.toString()));
    }
    private static int index(MpcSolanaTransactionCodec.Message message,String address) {
        for(int i=0;i<message.accounts().size();i++)if(message.accounts().get(i).getPublicKey().toBase58().equals(address))return i;
        throw new IllegalArgumentException("fixture account is absent");
    }
}

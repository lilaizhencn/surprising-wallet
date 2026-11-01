package com.surprising.wallet.chain.polkadot;

import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import tools.jackson.databind.JsonNode;

/** Verifies the gateway's original-runtime event decoding against the selected wire and public payment. */
public final class PolkadotExecutionReceipt {
    private static final HexFormat HEX=HexFormat.of();
    private PolkadotExecutionReceipt() { }
    public record Outcome(boolean paid,BigInteger feePlanck,long blockHeight) { }

    public static Outcome verify(JsonNode proof,MpcPolkadotTransactionCodec.Unsigned u,
                                 MpcPolkadotTransactionCodec.Signed selected) {
        MpcPolkadotTransactionCodec.verifySelected(u,selected);
        require(u.genesisHash().equals(proof.path("genesisHash").asText())
                && selected.txHash().equals(proof.path("txHash").asText())
                && selected.extrinsicHex().equals(proof.path("extrinsicHex").asText()),"Polkadot proof differs from original network or selected wire");
        require(u.specVersion()==proof.path("specVersion").asLong(-1)
                && u.transactionVersion()==proof.path("transactionVersion").asLong(-1),"Polkadot execution runtime differs from fixed signing domain");
        long height=proof.path("blockHeight").asLong(-1),latest=proof.path("finalizedHeight").asLong(-1);
        require(height>u.blockHeight() && height<u.validUntilHeight() && latest>=height,"Polkadot payment has no finalized inclusion within its original mortal era");
        String headerHex=proof.path("headerHex").asText();
        require(headerHex.matches("0x[0-9a-f]{196,32768}") && headerHex.length()%2==0,"Polkadot original header bytes are unavailable");
        byte[] header=HEX.parseHex(headerHex.substring(2));
        require(("0x"+HEX.formatHex(new Blake2b.Blake2b256().digest(header))).equals(proof.path("blockHash").asText()),"Polkadot original header hash differs");
        if(proof.has("parentHash"))require(("0x"+HEX.formatHex(Arrays.copyOf(header,32))).equals(proof.path("parentHash").asText()),"Polkadot parent header differs");
        require(headerHeight(header)==height,"Polkadot header number differs from inclusion height");
        require(proof.path("eventsHex").asText().length()<=1_048_578 && proof.path("eventsHex").asText().length()%2==0 && proof.path("eventsHex").asText().matches("0x[0-9a-f]+"),"Polkadot original event storage is unavailable");
        int index=proof.path("extrinsicIndex").asInt(-1),success=0,failure=0,transfers=0,fees=0;
        require(index>=0 && proof.path("events").isArray(),"Polkadot original extrinsic events are unavailable");
        BigInteger fee=null;
        for(var event:proof.path("events")) {
            if(event.has("index"))require(event.path("index").asInt(-1)==index,"Polkadot event belongs to another extrinsic");
            String section=event.path("section").asText(),method=event.path("method").asText();var data=event.path("data");
            if(section.equals("system") && method.equals("KilledAccount"))require(data.size()==1 && !sameAccount(data.path(0).asText(),u.sender()),"Polkadot payment reaped the published native nonce account");
            if(section.equals("system")) {if(method.equals("ExtrinsicSuccess"))success++;if(method.equals("ExtrinsicFailed"))failure++;}
            if(section.equals("transactionPayment") && method.equals("TransactionFeePaid")) {
                require(data.size()==3 && sameAccount(data.path(0).asText(),u.sender()) && atomic(data.path(2)).signum()==0,"Polkadot final fee payer or tip differs");
                fee=atomic(data.path(1));fees++;
            }
            if(section.equals("balances") && method.equals("Transfer")) {
                require(u.assetId()==null && data.size()==3 && sameAccount(data.path(0).asText(),u.sender())
                        && sameAccount(data.path(1).asText(),u.recipient()) && atomic(data.path(2)).equals(u.amountAtomic()),"Polkadot native principal event differs");transfers++;
            }
            if(section.equals("assets") && method.equals("Transferred")) {
                require(u.assetId()!=null && data.size()==4 && u.assetId().equals(data.path(0).asText())
                        && sameAccount(data.path(1).asText(),u.sender()) && sameAccount(data.path(2).asText(),u.recipient())
                        && atomic(data.path(3)).equals(u.amountAtomic()),"Polkadot asset principal event differs");transfers++;
            }
        }
        require(success+failure==1 && fees==1 && fee!=null && fee.signum()>0,"Polkadot terminal dispatch or actual native fee is ambiguous");
        require(success==1?transfers==1:transfers==0,"Polkadot principal transfer differs from dispatch outcome");
        return new Outcome(success==1,fee,height);
    }
    private static long headerHeight(byte[] header) {
        int first=header[32]&255,mode=first&3;
        if(mode==3){int length=(first>>>2)+4;require(length==4,"Polkadot header number exceeds compact u32");long value=0;for(int i=0;i<length;i++)value|=(header[33+i]&255L)<<(8*i);return value;}
        int length=mode==0?1:mode==1?2:4;long value=0;
        for(int i=0;i<length;i++)value|=(header[32+i]&255L)<<(8*i);
        return value>>>2;
    }
    private static BigInteger atomic(JsonNode node){String value=node.asText();require(value.matches("0|[1-9][0-9]{0,38}"),"Polkadot receipt amount is malformed");var result=new BigInteger(value);require(result.bitLength()<=128,"Polkadot receipt amount exceeds u128");return result;}
    private static boolean sameAccount(String a,String b){return Arrays.equals(MpcPolkadotTransactionCodec.account(a),MpcPolkadotTransactionCodec.account(b));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

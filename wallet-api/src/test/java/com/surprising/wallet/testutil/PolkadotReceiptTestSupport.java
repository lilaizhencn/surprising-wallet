package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import tools.jackson.databind.*;

/** Synthetic public observations for database boundaries; actual MPC/chain acceptance is separate. */
public final class PolkadotReceiptTestSupport {
    private PolkadotReceiptTestSupport() { }
    public static JsonNode payment(MpcPolkadotTransactionCodec.Unsigned u,MpcPolkadotTransactionCodec.Signed selected,boolean paid,long fee) {
        var json=new ObjectMapper();var proof=json.createObjectNode();
        byte[] header=new byte[99];ByteBuffer.wrap(header,32,2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)((123<<2)|1));
        proof.put("genesisHash",u.genesisHash()).put("finalizedHeight",123).put("blockHeight",123).put("blockHash","0x"+HexFormat.of().formatHex(new Blake2b.Blake2b256().digest(header)))
                .put("headerHex","0x"+HexFormat.of().formatHex(header)).put("txHash",selected.txHash()).put("extrinsicHex",selected.extrinsicHex()).put("extrinsicIndex",0)
                .put("specVersion",u.specVersion()).put("transactionVersion",u.transactionVersion()).put("eventsHex","0x00");
        var events=proof.putArray("events");
        if(paid) {
            var event=events.addObject().put("section",u.assetId()==null?"balances":"assets").put("method",u.assetId()==null?"Transfer":"Transferred");var data=event.putArray("data");
            if(u.assetId()!=null)data.add(u.assetId());data.add(u.sender()).add(u.recipient()).add(u.amountAtomic().toString());
        }
        events.addObject().put("section","transactionPayment").put("method","TransactionFeePaid").putArray("data").add(u.sender()).add(Long.toString(fee)).add("0");
        events.addObject().put("section","system").put("method",paid?"ExtrinsicSuccess":"ExtrinsicFailed").putArray("data").add("synthetic database boundary observation");
        return proof;
    }
}

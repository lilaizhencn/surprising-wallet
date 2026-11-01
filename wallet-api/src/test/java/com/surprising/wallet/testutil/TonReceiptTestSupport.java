package com.surprising.wallet.testutil;

import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;

import com.surprising.wallet.chain.ton.*;
import java.math.BigInteger;
import java.util.*;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.*;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.tlb.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic chain observations for PG fault/concurrency tests; live consensus has separate native probes. */
public final class TonReceiptTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TonReceiptTestSupport() { }
    public static TonExecutionReceipt.Trace nativePayment(MpcTonTransactionCodec.Unsigned payment,
                                                           MpcTonTransactionCodec.Signed selected, boolean skipped) {
        var fixture = fixture(skipped ? "skipped" : "native");
        var owner = parse(fixture.path("receipt"));
        owner.setAccountAddr(new BigInteger(1, Address.of(payment.sender()).hashPart));
        owner.getInOut().setIn(Message.deserialize(CellSlice.beginParse(Cell.fromBocBase64(selected.bocBase64()))));
        if (skipped) return new TonExecutionReceipt.Trace(receipt(owner,fixture.path("receipt")), null, null, List.of());
        var outbound = owner.getInOut().getOutMessages().getFirst();
        var info = (InternalMessageInfo)outbound.getInfo();
        info.setSrcAddr(MsgAddressIntStd.of(payment.sender())); info.setDstAddr(MsgAddressIntStd.of(payment.recipient()));
        info.getValue().setCoins(payment.amountAtomic()); outbound.setBody(MsgUtils.createTextMessageBody(payment.memo()));
        var recipient = parse(fixture.path("recipient"));
        recipient.setAccountAddr(new BigInteger(1, Address.of(payment.recipient()).hashPart));
        recipient.getInOut().setIn(outbound);
        var credit = ((TransactionDescriptionOrdinary)recipient.getDescription()).getCreditPhase();
        credit.setDueFeesCollected(null); credit.getCredit().setCoins(payment.amountAtomic());
        return new TonExecutionReceipt.Trace(receipt(owner,fixture.path("receipt")), null, receipt(recipient,fixture.path("recipient")), List.of());
    }
    private static JsonNode fixture(String name) {
        try (var in = TonReceiptTestSupport.class.getResourceAsStream("/mpc/ton-actual-" + name + "-receipt.json")) {
            return JSON.readTree(in);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static Transaction parse(JsonNode value) { return Transaction.deserialize(CellSlice.beginParse(Cell.fromBocBase64(value.path("data").asText()))); }
    /** Preserve raw VM/action/state cells; ton4j's general transaction serializers change their wire layout. */
    public static Cell serialize(Transaction tx,Cell original) {
        var refs=CellSlice.beginParse(original);refs.loadRef();var stateUpdate=refs.loadRef();
        var description=CellSlice.beginParse(refs.loadRef());description.loadUint(4);description.loadBit();
        if(description.loadBit())StoragePhase.deserialize(description);
        if(description.loadBit())CreditPhase.deserialize(description);
        var ordinary=(TransactionDescriptionOrdinary)tx.getDescription();
        var tail=new ArrayList<Boolean>();int bits=description.getRestBits();
        for(int i=0;i<bits;i++)tail.add(description.loadBit());
        if(ordinary.getBouncePhase()!=null || tail.get(bits-2))throw new IllegalArgumentException("native receipt fixture must not contain a bounce phase");
        tail.set(bits-3,ordinary.isAborted());
        var tailCell=CellBuilder.beginCell().storeBits(tail);
        while(description.getRefsCount()>0)tailCell.storeRef(description.loadRef());
        var desc=CellBuilder.beginCell().storeUint(0,4).storeBit(ordinary.isCreditFirst())
                .storeCellMaybe(ordinary.getStoragePhase()==null?null:ordinary.getStoragePhase().toCell())
                .storeCellMaybe(ordinary.getCreditPhase()==null?null:ordinary.getCreditPhase().toCell())
                .storeCell(tailCell.endCell()).endCell();
        return CellBuilder.beginCell().storeUint(7,4).storeUint(new BigInteger(tx.getAccountAddr(),16),256)
                .storeUint(tx.getLt(),64).storeUint(new BigInteger(tx.getPrevTxHash(),16),256).storeUint(tx.getPrevTxLt(),64)
                .storeUint(tx.getNow(),32).storeUint(tx.getOutMsgCount(),15)
                .storeCell(Transaction.serializeAccountState(tx.getOrigStatus())).storeCell(Transaction.serializeAccountState(tx.getEndStatus()))
                .storeRef(tx.getInOut().toCell()).storeCell(tx.getTotalFees().toCell())
                .storeRef(stateUpdate).storeRef(desc).endCell();
    }
    private static ObjectNode receipt(Transaction transaction,JsonNode original) {
        var cell = serialize(transaction,Cell.fromBocBase64(original.path("data").asText())); var value = JSON.createObjectNode();
        value.put("data", Base64.getEncoder().encodeToString(cell.toBoc(false)));
        value.putObject("transaction_id").put("hash", HexFormat.of().formatHex(cell.hash())).put("lt", transaction.getLt().toString());
        return value;
    }
}

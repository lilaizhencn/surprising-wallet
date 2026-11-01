package com.surprising.wallet.chain.ton;

import java.math.BigInteger;
import java.util.*;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.*;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.tlb.*;
import tools.jackson.databind.JsonNode;

/** Successful V4R2/TEP-74 execution proof. Unknown or failed hops never constitute a successful payment. */
public final class TonExecutionReceipt {
    private static final HexFormat HEX=HexFormat.of();
    private TonExecutionReceipt() { }
    public record Trace(JsonNode owner,JsonNode sourceJetton,JsonNode recipient,List<JsonNode> refunds) {
        public Trace {
            sourceJetton=sourceJetton==null || sourceJetton.isNull() || sourceJetton.isMissingNode()?null:sourceJetton;
            recipient=recipient==null || recipient.isNull() || recipient.isMissingNode()?null:recipient;
            refunds=List.copyOf(refunds);
        }
    }
    public record Success(BigInteger logicalTime,long feeNano) { }
    public record Failure(BigInteger logicalTime,long feeNano,String reason) { }
    public record Envelope(Cell raw,Message message) {
        public String hash(){return HEX.formatHex(raw.hash());}
    }
    public record Execution(Transaction transaction,Envelope incoming,List<Envelope> outgoing) {
        public Execution {outgoing=List.copyOf(outgoing);}
    }
    public static Execution read(JsonNode receipt,String expectedAddress) {
        require(receipt!=null && receipt.path("data").isTextual(),"TON transaction raw is absent");
        var raw=Cell.fromBocBase64(receipt.path("data").asText());
        require(CellSlice.beginParse(raw).loadUint(4).intValue()==7,"invalid TON transaction type");
        var tx=Transaction.deserialize(CellSlice.beginParse(raw));
        require(TonCenterClient.sameHash(receipt.path("transaction_id").path("hash").asText(),HEX.formatHex(raw.hash()))
                && new BigInteger(receipt.path("transaction_id").path("lt").asText()).equals(tx.getLt())
                && tx.getAccountAddr().equals(HEX.formatHex(Address.of(expectedAddress).hashPart)),"TON transaction raw, identity or logical time differs");
        require(tx.getTotalFees().getCoins().signum()>=0,"TON actual fee is negative");
        var io=CellSlice.beginParse(CellSlice.beginParse(raw).loadRef());
        require(io.loadBit(),"TON transaction has no incoming message");
        var incoming=io.loadRef();
        var dict=io.loadDictE(15,k->k.readUint(15),v->CellSlice.beginParse(v).loadRef());
        var outgoing=dict.elements.values().stream().map(Cell.class::cast).map(TonExecutionReceipt::envelope).toList();
        require(outgoing.size()==tx.getOutMsgCount(),"TON outgoing message count differs from raw");
        return new Execution(tx,envelope(incoming),outgoing);
    }
    public static Success verifySuccessful(Trace trace,MpcTonTransactionCodec.Unsigned payment,MpcTonTransactionCodec.Signed selected) {
        MpcTonTransactionCodec.verifySelected(payment,selected);
        var owner=read(trace.owner(),payment.sender());
        require(owner.incoming().hash().equals(selected.messageHashHex()),"TON owner did not consume selected MPC message");
        successful(owner);
        require(owner.outgoing().size()==1,"TON simple payment emitted an unexpected message count");
        var send=owner.outgoing().getFirst();var sendInfo=internal(send,payment.sender(),
                payment.jettonMaster()==null?payment.recipient():payment.sourceJettonWallet());
        require(sendInfo.getExtraFlags().signum()==0,"TON simple payment has unexpected extra flags");
        BigInteger fee=owner.transaction().getTotalFees().getCoins().add(sendInfo.getFwdFee());
        if(payment.jettonMaster()==null) {
            require(trace.sourceJetton()==null && trace.refunds().isEmpty() && !Boolean.TRUE.equals(sendInfo.getBounce())
                    && sendInfo.getValueCoins().equals(payment.amountAtomic())
                    && Arrays.equals(send.message().getBody().hash(),MsgUtils.createTextMessageBody(payment.memo()).hash()),"TON native delivery differs from fixed payment");
            var receiver=read(trace.recipient(),payment.recipient());consumes(receiver,send);
            require(receiver.transaction().getDescription() instanceof TransactionDescriptionOrdinary ordinary
                    && ordinary.getCreditPhase()!=null,"TON native principal has no credit phase");
            var credit=((TransactionDescriptionOrdinary)receiver.transaction().getDescription()).getCreditPhase();
            require(credit.getCredit().getCoins().add(credit.getDueFeesCollected()==null?BigInteger.ZERO:credit.getDueFeesCollected())
                    .equals(payment.amountAtomic()),"TON native principal differs from gross credit and receiver dues");
            // A non-bounceable transfer credits even an undeployed owner; receiver processing fees are separate.
        } else {
            require(sendInfo.getValueCoins().equals(MpcTonTransactionCodec.JETTON_ATTACHED_NANO)
                    && Boolean.TRUE.equals(sendInfo.getBounce()),"TON Jetton attachment differs");
            var source=read(trace.sourceJetton(),payment.sourceJettonWallet());consumes(source,send);successful(source);
            require(source.outgoing().size()==1,"TON source Jetton wallet emitted unexpected messages");
            var transfer=source.outgoing().getFirst();internal(transfer,payment.sourceJettonWallet(),payment.recipientJettonWallet());
            verifyInternalTransfer(transfer.message().getBody(),payment);
            var target=read(trace.recipient(),payment.recipientJettonWallet());consumes(target,transfer);successful(target);
            var expectedRefunds=new LinkedHashMap<String,Envelope>();
            for(var message:target.outgoing()) {
                String destination=message.message().getInfo().getDestinationAddress();
                var info=internal(message,payment.recipientJettonWallet(),destination);
                var body=CellSlice.beginParse(message.message().getBody());
                if(destination.equals(payment.sender())) {
                    require(body.loadUint(32).longValue()==0xd53276dbL && body.loadUint(64).longValueExact()==payment.queryId()
                            && body.getRestBits()==0 && body.getRefsCount()==0,"TON excess refund identity differs");
                    require(expectedRefunds.put(message.hash(),message)==null,"duplicate TON excess message");
                } else {
                    require(destination.equals(payment.recipient()) && info.getValueCoins().equals(MpcTonTransactionCodec.JETTON_FORWARD_NANO)
                            && body.loadUint(32).longValue()==0x7362d09cL && body.loadUint(64).longValueExact()==payment.queryId()
                            && body.loadCoins().equals(payment.amountAtomic())
                            && MpcTonTransactionCodec.canonicalAddress(body.loadAddress().toRaw()).equals(payment.sender()),"TON Jetton notification differs from exact delivery");
                    // Notification to an undeployed owner may bounce. The target Jetton credit is already committed.
                }
            }
            fee=fee.add(MpcTonTransactionCodec.JETTON_ATTACHED_NANO);
            require(trace.refunds().size()==expectedRefunds.size(),"TON actual excess refund trace is incomplete");
            for(var receipt:trace.refunds()) {
                var refund=read(receipt,payment.sender());var original=expectedRefunds.remove(refund.incoming().hash());
                require(original!=null,"TON refund is duplicated or unrelated");consumes(refund,original);successful(refund);
                require(refund.outgoing().isEmpty(),"TON excess recipient unexpectedly forwarded funds");
                fee=fee.subtract(refund.incoming().message().getInfo().getValueCoins()).add(refund.transaction().getTotalFees().getCoins());
            }
            require(expectedRefunds.isEmpty(),"TON excess refund evidence is missing");
        }
        require(fee.signum()>=0,"TON actual sender cost is negative");
        return new Success(owner.transaction().getLt(),fee.longValueExact());
    }
    /** mode=3 consumed the authenticated seqno but skipped its sole send; no principal left the owner. */
    public static Failure verifySkippedPayment(Trace trace,MpcTonTransactionCodec.Unsigned payment,
                                                MpcTonTransactionCodec.Signed selected) {
        MpcTonTransactionCodec.verifySelected(payment,selected);
        var owner=read(trace.owner(),payment.sender());
        require(owner.incoming().hash().equals(selected.messageHashHex()),"TON skipped receipt consumed another message");
        successful(owner);
        var action=((TransactionDescriptionOrdinary)owner.transaction().getDescription()).getActionPhase();
        require(action.isValid() && action.getTotalActions()==1 && action.getSpecActions()==0
                && action.getSkippedActions()==1 && action.getMessagesCreated()==0 && owner.outgoing().isEmpty()
                && trace.sourceJetton()==null && trace.recipient()==null && trace.refunds().isEmpty(),
                "TON skipped send lacks a consumed sequence and exact zero-payment proof");
        return new Failure(owner.transaction().getLt(),owner.transaction().getTotalFees().getCoins().longValueExact(),
                "TON_SEND_SKIPPED");
    }
    /** The owner consumed its seqno; the source Jetton VM aborted and returned only the exact TON bounce. */
    public static Failure verifyRejectedJetton(Trace trace,MpcTonTransactionCodec.Unsigned payment,
                                               MpcTonTransactionCodec.Signed selected) {
        require(payment.jettonMaster()!=null && trace.recipient()==null && trace.refunds().size()==1,"TON rejected Jetton trace is incomplete");
        MpcTonTransactionCodec.verifySelected(payment,selected);
        var owner=read(trace.owner(),payment.sender());successful(owner);
        require(owner.incoming().hash().equals(selected.messageHashHex()) && owner.outgoing().size()==1,"TON rejected Jetton owner differs from selected message");
        var send=owner.outgoing().getFirst();var info=internal(send,payment.sender(),payment.sourceJettonWallet());
        require(Boolean.TRUE.equals(info.getBounce()) && info.getValueCoins().equals(MpcTonTransactionCodec.JETTON_ATTACHED_NANO)
                && Arrays.equals(send.message().getBody().hash(),MpcTonTransactionCodec.payload(payment).hash()),"TON rejected source command differs from fixed payment");
        var source=read(trace.sourceJetton(),payment.sourceJettonWallet());consumes(source,send);
        require(source.transaction().getDescription() instanceof TransactionDescriptionOrdinary d && d.isAborted()
                && d.getBouncePhase() instanceof BouncePhaseOk && source.outgoing().size()==1,"TON source rejection has no exact zero-token-payment bounce proof");
        var bounce=source.outgoing().getFirst();
        require(bounce.message().getInfo() instanceof InternalMessageInfo,"TON rejected source emitted another message type");
        var returned=(InternalMessageInfo)bounce.message().getInfo();
        require(Boolean.TRUE.equals(returned.getBounced()) && !Boolean.TRUE.equals(returned.getBounce())
                && returned.getSourceAddress().equals(payment.sourceJettonWallet()) && returned.getDestinationAddress().equals(payment.sender())
                && returned.getValueCoins().signum()>=0,"TON rejection bounced to another identity");
        var bounced=CellSlice.beginParse(bounce.message().getBody());var original=CellSlice.beginParse(MpcTonTransactionCodec.payload(payment));
        int copied=Math.min(256,original.getRestBits());
        require(bounced.loadUint(32).longValue()==0xffff_ffffL && bounced.loadUint(copied).equals(original.loadUint(copied))
                && bounced.getRestBits()==0 && bounced.getRefsCount()==0,"TON bounce differs from original rejected command");
        var refund=read(trace.refunds().getFirst(),payment.sender());consumes(refund,bounce);successful(refund);
        require(refund.outgoing().isEmpty(),"TON rejection refund unexpectedly forwarded funds");
        BigInteger fee=owner.transaction().getTotalFees().getCoins().add(info.getFwdFee()).add(MpcTonTransactionCodec.JETTON_ATTACHED_NANO)
                .subtract(returned.getValueCoins()).add(refund.transaction().getTotalFees().getCoins());
        require(fee.signum()>=0,"TON rejected Jetton actual sender cost is negative");
        return new Failure(owner.transaction().getLt(),fee.longValueExact(),"TON_JETTON_SOURCE_REJECTED");
    }
    private static void verifyInternalTransfer(Cell raw,MpcTonTransactionCodec.Unsigned payment) {
        var body=CellSlice.beginParse(raw);
        require(body.loadUint(32).longValue()==0x178d4519L && body.loadUint(64).longValueExact()==payment.queryId()
                && body.loadCoins().equals(payment.amountAtomic())
                && MpcTonTransactionCodec.canonicalAddress(body.loadAddress().toRaw()).equals(payment.sender())
                && MpcTonTransactionCodec.canonicalAddress(body.loadAddress().toRaw()).equals(payment.sender())
                && body.loadCoins().equals(MpcTonTransactionCodec.JETTON_FORWARD_NANO),"TON internal Jetton transfer differs from fixed payment");
        boolean ref=body.loadBit();Cell memo=ref?body.loadRef():body.sliceToCell();
        memo.calculateHashes();
        require(Arrays.equals(memo.hash(),MsgUtils.createTextMessageBody(payment.memo()).hash())
                && (!ref || (body.getRestBits()==0 && body.getRefsCount()==0)),"TON internal transfer business memo differs");
    }
    private static void consumes(Execution execution,Envelope message){require(execution.incoming().hash().equals(message.hash()),"TON trace consumes a different internal message");}
    private static InternalMessageInfo internal(Envelope message,String source,String destination) {
        require(message.message().getInfo() instanceof InternalMessageInfo,"TON expected an internal delivery");
        var info=(InternalMessageInfo)message.message().getInfo();
        require(info.getSourceAddress().equals(source) && info.getDestinationAddress().equals(destination)
                && !Boolean.TRUE.equals(info.getBounced()) && info.getValueCoins().signum()>=0,"TON internal message identity differs");
        return info;
    }
    private static void successful(Execution execution){require(execution.transaction().getDescription() instanceof TransactionDescriptionOrdinary d
            && !d.isAborted() && d.getComputePhase() instanceof ComputePhaseVM vm && vm.isSuccess()
            && d.getActionPhase()!=null && d.getActionPhase().isSuccess(),"TON execution is not a proven successful payment");}
    private static Envelope envelope(Cell raw){
        var message=Message.deserialize(CellSlice.beginParse(raw));
        // Inline bodies are sliced into new cells; ton4j leaves their hashes empty until explicitly calculated.
        message.getBody().calculateHashes();
        return new Envelope(raw,message);
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

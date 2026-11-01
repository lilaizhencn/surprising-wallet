package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.TonSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.TonSubmissionService.Operation;
import com.surprising.wallet.service.TonSubmissionService.Kind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Public preparation and original-message recovery. Business writes use short submission transactions. */
@Service
@RequiredArgsConstructor
public class TonTransactionService {
    private final TonCenterClient rpc;
    private final TonSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final WalletRuntimeConfigService runtime;

    public String send(Operation op, ChainAddressRecord source) {
        runtime.requireTaskEnabled("TON", WalletRuntimeConfigService.TASK_WITHDRAW, "TON withdrawal");
        if (op.kind() != Kind.WITHDRAWAL) throw new IllegalArgumentException("withdrawal operation required");
        return transfer(op, source);
    }
    public String collect(UUID tenant, String reference, ChainAddressRecord source) {
        runtime.requireTaskEnabled("TON", WalletRuntimeConfigService.TASK_COLLECTION, "TON collection");
        var previous = collections.findTxHash(tenant, "TON", reference); if (previous.isPresent()) return previous.get();
        if (collections.claimSigningDurable(tenant, "TON", reference) != 1) throw new IllegalStateException("TON collection is claimed; recovery owns the intent");
        var op = new Operation(tenant, reference, Kind.COLLECTION);
        try { String hash = transfer(op, source); submissions.restoreSent(op, hash); return hash; }
        catch (RuntimeException error) { submissions.unknown(op, error.getClass().getSimpleName()); throw error; }
    }
    private String transfer(Operation op, ChainAddressRecord source) {
        TonSigningPayload draft = null;
        if (submissions.intent(op).isEmpty()) {
            var payment = submissions.payment(op); var profile = profiles.findAccountByChain("TON").orElseThrow();
            String owner = MpcTonTransactionCodec.canonicalAddress(source.getOwnerAddress());
            String master = null, sourceWallet = null, recipientWallet = null; int decimals = 9;
            if (!"TON".equals(payment.symbol())) {
                var token = tokens.findDefinition("TON", profile.getNetwork(), payment.symbol()).orElseThrow();
                master = MpcTonTransactionCodec.canonicalAddress(token.getContractAddress()); decimals = token.getDecimals();
                sourceWallet = rpc.jettonWallet(owner, master); recipientWallet = rpc.jettonWallet(payment.recipient(), master);
                var data = rpc.jettonData(sourceWallet);
                if (!owner.equals(data.owner()) || !master.equals(data.master())) throw new IllegalStateException("TON source Jetton getter differs from configured owner/master");
                if (!"TON".equals(source.getAssetSymbol()) && !sourceWallet.equals(MpcTonTransactionCodec.canonicalAddress(source.getAddress())))
                    throw new IllegalStateException("TON materialized source differs from configured master getter");
            }
            byte[] identity = identity(op); long queryId = master == null ? 0 : Math.max(1, ByteBuffer.wrap(identity).getLong() & Long.MAX_VALUE);
            String memo = op.kind().name().toLowerCase(Locale.ROOT) + ":" + HexFormat.of().formatHex(identity);
            var unsigned = new MpcTonTransactionCodec.Unsigned(profile.getNetwork(), owner, payment.recipient(),
                    payment.amount().movePointRight(decimals).toBigIntegerExact(), master, sourceWallet, recipientWallet,
                    rpc.seqno(owner), Instant.now().plusSeconds(600).getEpochSecond(), queryId, memo, submissions.publicKey(source));
            draft = new TonSigningPayload(payment.symbol(), payment.amount(), payment.customerFee(), decimals, source.getAddress(), unsigned);
        }
        var request = submissions.reserve(op, source, draft); submissions.sign(op, request.id());
        var signed = submissions.commit(op, request.id()); return broadcast(op, signed, submissions.payload(request));
    }
    public boolean recover(Operation op) {
        var request = submissions.intent(op); if (request.isEmpty()) return false;
        submissions.sign(op, request.get().id()); var signed = submissions.commit(op, request.get().id());
        String hash = broadcast(op, signed, submissions.payload(request.get())); submissions.restoreSent(op, hash); return true;
    }
    public int releaseUnsigned(Operation op) { return submissions.releaseUnsigned(op); }
    private String broadcast(Operation op, MpcTonTransactionCodec.Signed signed, TonSigningPayload payload) {
        if (rpc.findExternalMessageTransaction(payload.unsigned().sender(), signed.messageHashHex(), 100).isEmpty()) {
            if (Instant.now().getEpochSecond() >= payload.unsigned().validUntil()) throw new IllegalStateException("TON original message expired; reconciliation required");
            String observed = rpc.sendBoc(Base64.getDecoder().decode(signed.bocBase64()));
            if (!TonCenterClient.sameHash(observed, signed.messageHashHex())) throw new IllegalStateException("TON node returned a different selected message hash");
        }
        submissions.observed(op, signed.messageHashHex()); return signed.messageHashHex();
    }
    public boolean confirmWithdrawal(UUID tenant, String reference) { return confirm(new Operation(tenant, reference, Kind.WITHDRAWAL)); }
    public boolean confirmCollection(UUID tenant, String reference) { return confirm(new Operation(tenant, reference, Kind.COLLECTION)); }
    private boolean confirm(Operation op) {
        var profile = profiles.findAccountByChain("TON").orElseThrow();
        if (submissions.proof(op).isPresent()) return submissions.settle(op, null, masterSeqno(), profile.getWithdrawConfirmations());
        var request = submissions.intent(op).orElseThrow(); var payment = submissions.payload(request).unsigned();
        String hash = (op.kind() == Kind.WITHDRAWAL ? orders.findTxHash("TON", op.reference(), op.tenantId())
                : collections.findTxHash(op.tenantId(), "TON", op.reference())).orElseThrow();
        var ownerReceipt = rpc.findExternalMessageTransaction(payment.sender(), hash, 100); if (ownerReceipt.isEmpty()) return false;
        var owner = TonExecutionReceipt.read(ownerReceipt.get(), payment.sender());
        JsonNode sourceReceipt = null, recipientReceipt = null; var refunds = new ArrayList<JsonNode>();
        if (!owner.outgoing().isEmpty()) {
            if (owner.outgoing().size() != 1) throw new IllegalStateException("TON payment has unexpected owner messages");
            var first = owner.outgoing().getFirst();
            if (payment.jettonMaster() == null) {
                var recipient = rpc.findIncomingMessageTransaction(payment.recipient(), first.hash(), 100); if (recipient.isEmpty()) return false;
                recipientReceipt = recipient.get();
            } else {
                var source = rpc.findIncomingMessageTransaction(payment.sourceJettonWallet(), first.hash(), 100); if (source.isEmpty()) return false;
                sourceReceipt = source.get(); var execution = TonExecutionReceipt.read(sourceReceipt, payment.sourceJettonWallet());
                if(execution.transaction().getDescription() instanceof org.ton.ton4j.tlb.TransactionDescriptionOrdinary d && d.isAborted()) {
                    if(execution.outgoing().size()!=1)return false;
                    var refund=rpc.findIncomingMessageTransaction(payment.sender(),execution.outgoing().getFirst().hash(),100);if(refund.isEmpty())return false;
                    return submissions.settle(op,new TonExecutionReceipt.Trace(ownerReceipt.get(),sourceReceipt,null,List.of(refund.get())),masterSeqno(),profile.getWithdrawConfirmations());
                }
                var transfer = execution.outgoing().stream().filter(m -> payment.recipientJettonWallet().equals(m.message().getInfo().getDestinationAddress())).toList();
                if (transfer.size() != 1) return false;
                var recipient = rpc.findIncomingMessageTransaction(payment.recipientJettonWallet(), transfer.getFirst().hash(), 100); if (recipient.isEmpty()) return false;
                recipientReceipt = recipient.get();
                for (var message : TonExecutionReceipt.read(recipientReceipt, payment.recipientJettonWallet()).outgoing()) {
                    if (!payment.sender().equals(message.message().getInfo().getDestinationAddress())) continue;
                    var excess = rpc.findIncomingMessageTransaction(payment.sender(), message.hash(), 100); if (excess.isEmpty()) return false;
                    refunds.add(excess.get());
                }
            }
        }
        return submissions.settle(op, new TonExecutionReceipt.Trace(ownerReceipt.get(), sourceReceipt, recipientReceipt, refunds),
                masterSeqno(), profile.getWithdrawConfirmations());
    }
    private long masterSeqno() {
        long value = rpc.masterchainInfo().path("last").path("seqno").asLong();
        if (value <= 0) throw new IllegalStateException("TON masterchain sequence is absent"); return value;
    }
    private static byte[] identity(Operation op) {
        try { return MessageDigest.getInstance("SHA-256").digest((op.tenantId() + ":" + op.kind() + ":" + op.reference()).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

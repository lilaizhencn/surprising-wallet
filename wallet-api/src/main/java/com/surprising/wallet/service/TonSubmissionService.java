package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import com.surprising.wallet.common.signing.TonSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.TonTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Owned seqno/intent, remote MPC, durable BOC/hash, and atomic receipt settlement. */
@Service
@RequiredArgsConstructor
public class TonSubmissionService {
    private static final HexFormat HEX = HexFormat.of();
    private static final String SLOT = "transaction", ENCODING = "TON_V4R2_PAYMENT_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final TonTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final TokenConfigRepository tokens;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;

    public enum Kind { WITHDRAWAL, COLLECTION }
    public record Operation(UUID tenantId, String reference, Kind kind) {
        public Operation {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(kind);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("TON business reference required");
        }
    }
    public record Payment(String symbol, String recipient, BigDecimal amount, BigDecimal customerFee) { }
    public record Observed(TonExecutionReceipt.Trace trace, long firstMasterSeqno) { }

    public Optional<MpcSignRequest> intent(Operation op) {
        return requests.findBusiness(op.tenantId(), ChainType.TON,
                MpcSignCommand.BusinessType.valueOf(op.kind().name()), op.reference(), SLOT);
    }
    public String publicKey(ChainAddressRecord source) {
        return signing.requireKey(source.getTenantId(), source.getMpcKeyId()).publicKeyHex();
    }
    public TonSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain() == ChainType.TON && ENCODING.equals(request.command().payloadEncoding()), "TON intent encoding differs");
        var payload = json.readValue(HEX.parseHex(request.command().payloadHex()), TonSigningPayload.class);
        require(HEX.formatHex(MpcTonTransactionCodec.signingBytes(payload.unsigned())).equals(request.command().preimageHex()), "TON fixed signing hash differs");
        return payload;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public Payment payment(Operation op) {
        var owner = lockOwner(op);
        return new Payment(owner.symbol(), owner.recipient(), owner.amount(), owner.fee());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public MpcSignRequest reserve(Operation op, ChainAddressRecord source, TonSigningPayload draft) {
        var owner = lockOwner(op);
        require(op.tenantId().equals(source.getTenantId()) && "TON".equals(source.getChain())
                && owner.sender().equals(source.getAddress()), "TON source differs from owned business");
        var previous = intent(op);
        if (previous.isPresent()) {
            require(previous.get().command().keyId().equals(source.getMpcKeyId()), "TON source key changed");
            validate(owner, payload(previous.get()));
            return previous.get();
        }
        require(owner.hash() == null && "SIGNING".equals(owner.state()), "TON owner is not available for signing");
        validate(owner, draft);
        var stored = addresses.findOwned(op.tenantId(), source.getId()).orElseThrow();
        require(Boolean.TRUE.equals(stored.getEnabled()) && Objects.equals(stored.getMpcKeyId(), source.getMpcKeyId())
                && Objects.equals(stored.getAddress(), source.getAddress()), "TON source is not the published owned address");
        var profile = profiles.findAccountByChain("TON").orElseThrow();
        var key = signing.lockActiveKey(op.tenantId(), source.getMpcKeyId(), ChainType.TON);
        var nativeOwner = addresses.find("TON", "TON", stored.getUserId(), stored.getBiz(),
                stored.getAddressIndex(), stored.getWalletRole()).orElseThrow();
        var u = draft.unsigned();
        require(op.tenantId().equals(nativeOwner.getTenantId()) && Boolean.TRUE.equals(nativeOwner.getEnabled())
                && key.id().equals(nativeOwner.getMpcKeyId()) && key.userId() == stored.getUserId()
                && key.biz() == stored.getBiz() && key.addressIndex() == stored.getAddressIndex()
                && Objects.equals(stored.getAccountId(), nativeOwner.getAccountId())
                && canonical(nativeOwner.getAddress()).equals(u.sender()) && canonical(stored.getOwnerAddress()).equals(u.sender())
                && canonical(MpcPublicAddressCodec.encode(profile, HEX.parseHex(key.publicKeyHex()))).equals(u.sender())
                && key.publicKeyHex().equals(u.publicKeyHex()) && profile.getNetwork().equals(u.network()), "TON signer differs from published network joint key");
        if ("TON".equals(owner.symbol())) {
            require("TON".equals(stored.getAssetSymbol()) && draft.decimals() == 9 && u.jettonMaster() == null, "TON native source or precision differs");
        } else {
            var token = tokens.findDefinition("TON", profile.getNetwork(), owner.symbol()).orElseThrow();
            require(draft.decimals() == token.getDecimals() && canonical(token.getContractAddress()).equals(u.jettonMaster()), "TON configured Jetton master or precision differs");
            if (!"TON".equals(stored.getAssetSymbol())) require(owner.symbol().equals(stored.getAssetSymbol())
                    && canonical(stored.getAddress()).equals(u.sourceJettonWallet()), "TON materialized source wallet differs");
        }
        if (op.kind() == Kind.COLLECTION) {
            var collector = addresses.findEnabledByTenantAndAddress(op.tenantId(), "TON", "TON", owner.recipient()).orElseThrow();
            require(collector.getMpcKeyId() != null && canonical(collector.getAddress()).equals(u.recipient()), "TON collector has no owned joint key");
        }
        long sequence = sequences.reserve("TON", u.sender(), u.sequence(),MpcSignCommand.reservationOwner(op.tenantId(),MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference()));
        var fixed = new TonSigningPayload(draft.symbol(), draft.amount(), draft.customerFee(), draft.decimals(), draft.sourceAddress(),
                new MpcTonTransactionCodec.Unsigned(u.network(), u.sender(), u.recipient(), u.amountAtomic(), u.jettonMaster(),
                        u.sourceJettonWallet(), u.recipientJettonWallet(), sequence, u.validUntil(), u.queryId(), u.memo(), u.publicKeyHex()));
        return signing.reserveSignature(new MpcSignCommand(op.tenantId(), key.id(), key.activeEpoch(), ChainType.TON,
                MpcSignCommand.BusinessType.valueOf(op.kind().name()), op.reference(), SLOT, MpcSignCommand.Purpose.TRANSACTION,
                ENCODING, HEX.formatHex(json.writeValueAsBytes(fixed)), HEX.formatHex(MpcTonTransactionCodec.signingBytes(fixed.unsigned()))), nodes.online());
    }
    public void sign(Operation op, UUID requestId) {
        require(intent(op).orElseThrow().id().equals(requestId), "TON signature request differs from owner");
        signing.awaitSignature(op.tenantId(), requestId, nodes);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public MpcTonTransactionCodec.Signed commit(Operation op, UUID requestId) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow();
        require(request.id().equals(requestId), "TON selected request differs from owner");
        var payload = payload(request); validate(owner, payload); var signed = selected(op, request);
        if (owner.hash() != null) {
            require(owner.hash().equals(signed.messageHashHex()) && transactions.findSigned(owner.hash()).orElseThrow().getRawPayload().equals(signed.bocBase64()), "TON durable BOC/hash differs from selected signature");
            return signed;
        }
        require("SIGNING".equals(owner.state()) || "BROADCAST_UNKNOWN".equals(owner.state()), "TON owner cannot bind BOC");
        transactions.upsert(record(owner, payload, signed, "PREPARED", null, 0, 0, null));
        require((op.kind() == Kind.WITHDRAWAL ? orders.bindPrepared(op.tenantId(), "TON", op.reference(), signed.messageHashHex())
                : collections.bindPrepared(op.tenantId(), "TON", op.reference(), signed.messageHashHex())) == 1, "TON BOC/hash binding raced");
        return signed;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void observed(Operation op, String hash) {
        var owner = lockOwner(op); require(hash.equals(owner.hash()) && intent(op).isPresent(), "TON observed hash differs from owner");
        transactions.markSubmitted(hash);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void restoreSent(Operation op, String hash) {
        var owner = lockOwner(op); require(hash.equals(owner.hash()) && intent(op).isPresent(), "TON recovery differs from owner");
        if (!("SIGNING".equals(owner.state()) || "BROADCAST_UNKNOWN".equals(owner.state()))) return;
        require((op.kind() == Kind.WITHDRAWAL ? orders.recoverPreparedSent(op.tenantId(), "TON", op.reference(), hash)
                : collections.recoverPreparedSent(op.tenantId(), "TON", op.reference(), hash)) == 1, "TON SENT recovery raced");
        transactions.markSubmitted(hash);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void unknown(Operation op, String reason) {
        var owner = lockOwner(op); if (!"SIGNING".equals(owner.state())) return;
        if (op.kind() == Kind.WITHDRAWAL) orders.markBroadcastUnknown(op.tenantId(), "TON", op.reference(), owner.sender(), reason);
        else collections.markSigningUnknown(op.tenantId(), "TON", op.reference(), reason);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public int releaseUnsigned(Operation op) {
        var owner = lockOwner(op); if (owner.hash() != null || intent(op).isPresent()) return 0;
        return op.kind() == Kind.WITHDRAWAL ? orders.recoverUnsignedDurable(op.tenantId(), "TON", op.reference())
                : collections.recoverUnsignedDurable(op.tenantId(), "TON", op.reference());
    }
    public Optional<Observed> proof(Operation op) {
        var request = intent(op).orElseThrow(); var signed = selected(op, request);
        return transactions.findSigned(signed.messageHashHex()).map(TonTransactionRecord::getExecutionReceipt)
                .map(value -> json.readValue(value, Observed.class));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public boolean settle(Operation op, TonExecutionReceipt.Trace trace, long latestMasterSeqno, int requiredConfirmations) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow(); var payload = payload(request);
        validate(owner, payload); var signed = selected(op, request);
        require(signed.messageHashHex().equals(owner.hash()), "TON receipt differs from owned message hash");
        var durable = transactions.findSigned(owner.hash()).orElseThrow();
        require(durable.getRawPayload().equals(signed.bocBase64()), "TON receipt has no identical durable BOC");
        if ("CONFIRMED".equals(owner.state()) || "FAILED".equals(owner.state())) return false;
        require("SENT".equals(owner.state()) || "CONFIRMING".equals(owner.state()), "TON owner is not confirmable");
        require(latestMasterSeqno > 0, "TON masterchain observation is absent");
        var observed = durable.getExecutionReceipt() == null ? new Observed(Objects.requireNonNull(trace), latestMasterSeqno)
                : json.readValue(durable.getExecutionReceipt(), Observed.class);
        require(observed.firstMasterSeqno() > 0 && latestMasterSeqno >= observed.firstMasterSeqno(), "TON observation height moved backwards");
        var execution = TonExecutionReceipt.read(observed.trace().owner(), payload.unsigned().sender());
        boolean paid = !execution.outgoing().isEmpty(); BigInteger lt; long fee; String failure = null;
        boolean rejected = paid && payload.unsigned().jettonMaster()!=null && observed.trace().sourceJetton()!=null
                && TonExecutionReceipt.read(observed.trace().sourceJetton(),payload.unsigned().sourceJettonWallet()).transaction().getDescription()
                    instanceof org.ton.ton4j.tlb.TransactionDescriptionOrdinary d && d.isAborted();
        if (rejected) { var result=TonExecutionReceipt.verifyRejectedJetton(observed.trace(),payload.unsigned(),signed);paid=false;lt=result.logicalTime();fee=result.feeNano();failure=result.reason(); }
        else if (paid) { var result = TonExecutionReceipt.verifySuccessful(observed.trace(), payload.unsigned(), signed); lt = result.logicalTime(); fee = result.feeNano(); }
        else { var result = TonExecutionReceipt.verifySkippedPayment(observed.trace(), payload.unsigned(), signed); lt = result.logicalTime(); fee = result.feeNano(); failure = result.reason(); }
        long confirmations = latestMasterSeqno - observed.firstMasterSeqno() + 1;
        boolean ready = confirmations >= Math.max(1, requiredConfirmations);
        transactions.upsert(record(owner, payload, signed, ready ? (paid ? "CONFIRMED" : "FAILED") : "CONFIRMING",
                lt, (int)Math.min(Integer.MAX_VALUE, confirmations), fee, json.writeValueAsString(observed)));
        if (!ready) return false;
        if (op.kind() == Kind.WITHDRAWAL) return paid ? settlements.confirm(op.tenantId(), "TON", op.reference(), owner.hash())
                : settlements.fail(op.tenantId(), "TON", op.reference(), owner.hash(), failure);
        require(collections.updateAmounts(op.tenantId(), "TON", op.reference(), owner.amount(), BigDecimal.valueOf(fee).movePointLeft(9)) == 1, "TON collection fee update raced");
        require((paid ? collections.markConfirmed(op.tenantId(), "TON", op.reference(), owner.hash())
                : collections.updateStatus(op.tenantId(), "TON", op.reference(), "FAILED", owner.hash(), failure, null)) == 1, "TON collection settlement raced");
        return true;
    }
    private MpcTonTransactionCodec.Signed selected(Operation op, MpcSignRequest request) {
        require(request.state() == MpcSignRequest.State.COMPLETED, "TON MPC result is not completed");
        var payload = payload(request);
        require(signing.requireKey(op.tenantId(), request.command().keyId()).publicKeyHex().equals(payload.unsigned().publicKeyHex()), "TON selected joint key differs");
        return MpcTonTransactionCodec.assemble(payload.unsigned(), signing.signature(op.tenantId(), request.id()).orElseThrow());
    }
    private static void validate(Owner owner, TonSigningPayload payload) {
        require(payload != null && owner.amount().signum() > 0 && owner.amount().compareTo(payload.amount()) == 0
                && owner.fee().compareTo(payload.customerFee()) == 0 && owner.symbol().equals(payload.symbol())
                && owner.sender().equals(payload.sourceAddress()) && canonical(owner.recipient()).equals(payload.unsigned().recipient())
                && payload.decimals() >= 0 && payload.decimals() <= 18
                && owner.amount().movePointRight(payload.decimals()).toBigIntegerExact().equals(payload.unsigned().amountAtomic()), "TON payload differs from exact owned payment");
        require(("TON".equals(owner.symbol()) && payload.decimals() == 9 && payload.unsigned().jettonMaster() == null)
                || (!"TON".equals(owner.symbol()) && payload.unsigned().jettonMaster() != null), "TON asset kind differs");
    }
    private Owner lockOwner(Operation op) {
        if (op.kind() == Kind.WITHDRAWAL) {
            var row = orders.lockForSettlement(op.tenantId(), "TON", op.reference()).orElseThrow();
            return new Owner(row.getStatus(), row.getFromAddress(), row.getToAddress(), row.getAssetSymbol(), row.getAmount(), row.getFee(), row.getTxHash());
        }
        var row = collections.lockForSubmission(op.tenantId(), "TON", op.reference()).orElseThrow();
        return new Owner(row.getStatus(), row.getFromAddress(), row.getToAddress(), row.getAssetSymbol(), row.getAmount(), BigDecimal.ZERO, row.getTxHash());
    }
    private static TonTransactionRecord record(Owner owner, TonSigningPayload payload, MpcTonTransactionCodec.Signed signed,
                                               String status, BigInteger lt, int confirmations, long fee, String proof) {
        return TonTransactionRecord.builder().chain("TON").txHash(signed.messageHashHex()).fromAddress(owner.sender())
                .toAddress(owner.recipient()).assetSymbol(payload.symbol()).jettonMaster(payload.unsigned().jettonMaster())
                .amount(owner.amount()).feeNano(fee).logicalTime(lt).confirmations(confirmations).status(status)
                .rawPayload(signed.bocBase64()).executionReceipt(proof).build();
    }
    private record Owner(String state, String sender, String recipient, String symbol, BigDecimal amount, BigDecimal fee, String hash) { }
    private static String canonical(String address) { return MpcTonTransactionCodec.canonicalAddress(address); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.cardano.CardanoExecutionReceipt;
import com.surprising.wallet.chain.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.chain.model.CardanoTransactionRecord;
import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.sync.Sync;

/** Owned input admission -> remote MPC -> durable exact wire -> proven final settlement. */
@Service
@RequiredArgsConstructor
public class CardanoSubmissionService {
    public static final String SCANNER = "cardano-native-scanner";
    private static final String SLOT = "transaction", ENCODING = "CARDANO_PAYMENT_JSON";
    private static final HexFormat HEX = HexFormat.of();
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final CardanoTransactionRepository transactions;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final ChainScanHeightRepository heights;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    private final CustodyGasAccountingService accounting;

    public enum Kind { WITHDRAWAL, COLLECTION, FUNDING }
    public record Operation(UUID tenantId, String reference, Kind kind) {
        public Operation {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(kind);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Cardano business reference required");
        }
    }
    public record Payment(String symbol, String recipient, BigDecimal amount, BigDecimal customerFee) { }

    public Optional<MpcSignRequest> intent(Operation op) {
        return requests.findBusiness(op.tenantId(), ChainType.ADA, MpcSignCommand.BusinessType.valueOf(op.kind().name()), op.reference(), SLOT);
    }
    public String publicKey(ChainAddressRecord source) { return signing.requireKey(source.getTenantId(), source.getMpcKeyId()).publicKeyHex(); }
    public CardanoSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain() == ChainType.ADA && ENCODING.equals(request.command().payloadEncoding()), "Cardano request encoding differs");
        var p = json.readValue(HEX.parseHex(request.command().payloadHex()), CardanoSigningPayload.class);
        require(HEX.formatHex(MpcCardanoTransactionCodec.signingBytes(p.unsigned())).equals(request.command().preimageHex()), "Cardano immutable preimage differs");
        return p;
    }
    public Optional<CardanoTransactionRecord> stored(Operation op) { return intent(op).flatMap(request -> transactions.find(op.tenantId(), request.id())); }
    public Optional<CardanoSigningPayload> withdrawal(UUID tenantId, String hash) {
        return transactions.findByHash(tenantId, hash).flatMap(row -> requests.find(tenantId, row.requestId()))
                .filter(request -> request.command().businessType() == MpcSignCommand.BusinessType.WITHDRAWAL).map(this::payload);
    }
    public boolean terminal(Operation op) { return stored(op).map(row -> Set.of("CONFIRMED", "FAILED").contains(row.status())).orElse(false); }
    public boolean paid(Operation op) { return stored(op).map(row -> "CONFIRMED".equals(row.status())).orElse(false); }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public Payment payment(Operation op) {
        var owner = Objects.requireNonNull(lockOwner(op));
        return new Payment(owner.symbol(), owner.recipient(), owner.amount(), owner.fee());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public MpcSignRequest reserve(Operation op, ChainAddressRecord source, CardanoSigningPayload draft) {
        var owner = lockOwner(op);
        require(op.tenantId().equals(source.getTenantId()) && "ADA".equals(source.getChain())
                && (owner == null || owner.sender().equals(source.getAddress())), "Cardano source differs from owned business");
        var stored = addresses.findOwned(op.tenantId(), source.getId()).orElseThrow();
        require(Boolean.TRUE.equals(stored.getEnabled()) && Objects.equals(stored.getMpcKeyId(), source.getMpcKeyId())
                && stored.getAddress().equals(source.getAddress()), "Cardano source is not the published owned address");
        var key = signing.lockActiveKey(op.tenantId(), stored.getMpcKeyId(), ChainType.ADA);
        var previous = intent(op);
        if (previous.isPresent()) {
            require(previous.get().command().keyId().equals(key.id()), "Cardano source key changed");
            validate(op, owner, payload(previous.get())); return previous.get();
        }
        require(owner == null || (owner.hash() == null && "SIGNING".equals(owner.state())), "Cardano owner is not available for signing");
        validate(op, owner, draft);
        var profile = profiles.findAccountByChain("ADA").orElseThrow();
        var u = draft.unsigned();
        require(draft.requiredConfirmations() == Math.max(1, profile.getWithdrawConfirmations()), "Cardano draft changed the configured confirmation policy");
        require(key.userId() == stored.getUserId() && key.biz() == stored.getBiz() && key.addressIndex() == stored.getAddressIndex()
                && key.publicKeyHex().equals(u.publicKeyHex()) && stored.getAddress().equals(u.sender())
                && MpcPublicAddressCodec.encode(profile, HEX.parseHex(key.publicKeyHex())).equals(u.sender()),
                "Cardano signer differs from the published joint key");
        var scan = heights.nativeState("ADA", SCANNER, false).orElseThrow(() -> new IllegalStateException("Cardano address has no initial scan anchor"));
        require(scan.genesisHash().equals(u.genesisHash()), "Cardano payment node differs from the published scan network");
        require(assets.findActiveDefinition("ADA", "ADA").orElseThrow().getDecimals() == 6, "Cardano native precision must be six");
        if ("ADA".equals(draft.symbol())) require(draft.decimals() == 6 && "lovelace".equals(u.unit()), "Cardano native unit or precision differs");
        else {
            var token = tokens.findDefinition("ADA", profile.getNetwork(), draft.symbol()).orElseThrow();
            require(token.getDecimals() == draft.decimals() && MpcCardanoTransactionCodec.assetUnit(token.getContractAddress()).equals(u.unit()),
                    "Cardano token policy, asset name or precision differs");
        }
        if (op.kind() != Kind.WITHDRAWAL) require(addresses.findEnabledByTenantAndAddress(op.tenantId(), "ADA", u.recipient()).orElseThrow().getMpcKeyId() != null,
                "Cardano collection/funding target has no owned joint key");
        require(!transactions.hasPendingSender(u.sender()), "Cardano source already has an unresolved payment");
        require(transactions.lastTerminalHeight(u.sender()) <= draft.observed().height(), "Cardano snapshot precedes the source's last settled input spend");
        var request = signing.reserveSignature(new MpcSignCommand(op.tenantId(), key.id(), key.activeEpoch(), ChainType.ADA,
                MpcSignCommand.BusinessType.valueOf(op.kind().name()), op.reference(), SLOT, MpcSignCommand.Purpose.TRANSACTION,
                ENCODING, HEX.formatHex(json.writeValueAsBytes(draft)), HEX.formatHex(MpcCardanoTransactionCodec.signingBytes(u))), nodes.online());
        require(transactions.insertPending(request.id(), op.tenantId(), u.sender(), MpcCardanoTransactionCodec.txHash(u), draft.observed()) == 1,
                "Cardano input admission raced");
        if (op.kind() == Kind.FUNDING) require(accounting.reservePreparation(op.tenantId(), request.id(), u.sender(),
                draft.amount().add(lovelace(MpcCardanoTransactionCodec.fee(u)))), "Cardano funding requires a funded owned tenant Gas account");
        return request;
    }

    public void sign(Operation op, UUID requestId) {
        require(intent(op).orElseThrow().id().equals(requestId), "Cardano signature request differs from owner");
        signing.awaitSignature(op.tenantId(), requestId, nodes);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public MpcCardanoTransactionCodec.Signed commit(Operation op, UUID requestId) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow();
        require(request.id().equals(requestId), "Cardano selected request differs from owner");
        validate(op, owner, payload(request)); var signed = selected(op, request);
        var row = transactions.find(op.tenantId(), requestId).orElseThrow();
        require(row.txHash().equals(signed.txHash()), "Cardano fixed body hash changed");
        if (row.rawPayload() != null) {
            require(row.rawPayload().equals(signed.transactionHex()) && (owner == null || signed.txHash().equals(owner.hash())), "Cardano durable raw or business hash differs");
            return signed;
        }
        require(owner == null || Set.of("SIGNING", "BROADCAST_UNKNOWN").contains(owner.state()), "Cardano owner cannot bind raw");
        require(transactions.bindSigned(op.tenantId(), requestId, signed.txHash(), signed.transactionHex()) == 1, "Cardano wire binding raced");
        if (owner != null) require((op.kind() == Kind.WITHDRAWAL ? orders.bindPrepared(op.tenantId(), "ADA", op.reference(), signed.txHash())
                : collections.bindPrepared(op.tenantId(), "ADA", op.reference(), signed.txHash())) == 1, "Cardano raw/business binding raced");
        return signed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void observed(Operation op, String hash) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow();
        require(selected(op, request).txHash().equals(hash) && (owner == null || hash.equals(owner.hash())), "Cardano observed hash differs");
        transactions.markSubmitted(op.tenantId(), request.id());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void restoreSent(Operation op, String hash) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow();
        require(selected(op, request).txHash().equals(hash) && (owner == null || hash.equals(owner.hash())), "Cardano recovered hash differs");
        if (owner != null && Set.of("SIGNING", "BROADCAST_UNKNOWN").contains(owner.state()))
            require((op.kind() == Kind.WITHDRAWAL ? orders.recoverPreparedSent(op.tenantId(), "ADA", op.reference(), hash)
                    : collections.recoverPreparedSent(op.tenantId(), "ADA", op.reference(), hash)) == 1, "Cardano SENT recovery raced");
        transactions.markSubmitted(op.tenantId(), request.id());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void unknown(Operation op, String reason) {
        var owner = lockOwner(op);
        if (owner == null || !"SIGNING".equals(owner.state())) return;
        if (op.kind() == Kind.WITHDRAWAL) orders.markBroadcastUnknown(op.tenantId(), "ADA", op.reference(), owner.sender(), reason);
        else collections.markSigningUnknown(op.tenantId(), "ADA", op.reference(), reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public int releaseUnsigned(Operation op) {
        var owner = Objects.requireNonNull(lockOwner(op));
        if (owner.hash() != null || intent(op).isPresent()) return 0;
        return op.kind() == Kind.WITHDRAWAL ? orders.recoverUnsignedDurable(op.tenantId(), "ADA", op.reference())
                : collections.recoverUnsignedDurable(op.tenantId(), "ADA", op.reference());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public boolean searched(Operation op, ChainScanPoint expected, ChainScanPoint next) {
        lockOwner(op); var request = intent(op).orElseThrow();
        return transactions.advanceSearch(op.tenantId(), request.id(), expected, next) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public boolean settle(Operation op, JsonNode proof, long latestHeight) {
        var owner = lockOwner(op); var request = intent(op).orElseThrow(); var p = payload(request);
        validate(op, owner, p); var signed = selected(op, request);
        var row = transactions.find(op.tenantId(), request.id()).orElseThrow();
        require(signed.txHash().equals(row.txHash()) && signed.transactionHex().equals(row.rawPayload())
                && (owner == null || signed.txHash().equals(owner.hash())), "Cardano receipt has no selected owned durable wire");
        if (Set.of("CONFIRMED", "FAILED").contains(row.status())) return false;
        require(owner == null || Set.of("SENT", "CONFIRMING").contains(owner.state()), "Cardano owner is not confirmable");
        require(proof != null && p.unsigned().genesisHash().equals(proof.path("genesisHash").asText()), "Cardano proof network differs");
        boolean paid;
        CardanoExecutionReceipt.Outcome outcome;
        if ("INCLUDED".equals(proof.path("kind").asText())) {
            outcome = CardanoExecutionReceipt.verify(block(proof), p.unsigned(), signed); paid = true;
        } else {
            require("EXPIRED_UNSPENT".equals(proof.path("kind").asText()), "unknown Cardano execution proof");
            outcome = CardanoExecutionReceipt.expiredUnspent(block(proof), p.unsigned(), signed, row.searched(), unspent(proof), p.requiredConfirmations());
            paid = false;
        }
        if (latestHeight < outcome.blockHeight() || latestHeight - outcome.blockHeight() < p.requiredConfirmations() - 1L) return false;
        // Only proven depth-qualified facts enter the immutable receipt, never an unsafe intermediate inclusion.
        BigDecimal expense = lovelace(outcome.feeLovelace());
        if (paid && op.kind() == Kind.FUNDING) expense = expense.add(p.amount());
        else if (paid && op.kind() == Kind.WITHDRAWAL && !"ADA".equals(p.symbol())) expense = expense.add(lovelace(MpcCardanoTransactionCodec.recipientLovelace(p.unsigned())));
        require(transactions.recordProof(op.tenantId(), request.id(), json.writeValueAsString(proof), outcome.feeLovelace(),
                expense.movePointRight(6).toBigIntegerExact(), outcome.blockHeight(), paid ? "CONFIRMED" : "FAILED") == 1, "Cardano proof recording raced");
        if (owner == null) { accounting.settlePreparation(op.tenantId(), request.id(), expense, signed.txHash()); return true; }
        if (op.kind() == Kind.WITHDRAWAL) return paid ? settlements.confirm(op.tenantId(), "ADA", op.reference(), signed.txHash())
                : settlements.fail(op.tenantId(), "ADA", op.reference(), signed.txHash(), "Cardano TTL closed after complete canonical search; original inputs remain unspent");
        require(collections.updateAmounts(op.tenantId(), "ADA", op.reference(), owner.amount(), expense) == 1, "Cardano collection actual expense update raced");
        require((paid ? collections.markConfirmed(op.tenantId(), "ADA", op.reference(), signed.txHash())
                : collections.updateStatus(op.tenantId(), "ADA", op.reference(), "FAILED", signed.txHash(), "Cardano TTL closed; original inputs remain unspent", null)) == 1,
                "Cardano collection settlement raced");
        return true;
    }

    private MpcCardanoTransactionCodec.Signed selected(Operation op, MpcSignRequest request) {
        require(request.state() == MpcSignRequest.State.COMPLETED, "Cardano MPC result is incomplete");
        var p = payload(request);
        require(signing.requireKey(op.tenantId(), request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()), "Cardano selected joint key differs");
        return MpcCardanoTransactionCodec.assemble(p.unsigned(), signing.signature(op.tenantId(), request.id()).orElseThrow());
    }
    private void validate(Operation op, Owner owner, CardanoSigningPayload p) {
        require(p != null && p.amount() != null && p.amount().signum() > 0 && p.customerFee() != null && p.customerFee().signum() >= 0
                && p.decimals() >= 0 && p.decimals() <= 24 && p.requiredConfirmations() > 0 && p.requiredConfirmations() <= 10_000
                && p.observed() != null && p.observed().slot() <= MpcCardanoTransactionCodec.validFrom(p.unsigned())
                && p.amount().movePointRight(p.decimals()).toBigIntegerExact().equals(p.unsigned().amount()), "Cardano exact amount or accepted policy differs");
        if (owner != null) require(owner.symbol().equals(p.symbol()) && owner.amount().compareTo(p.amount()) == 0 && owner.fee().compareTo(p.customerFee()) == 0
                && owner.sender().equals(p.unsigned().sender()) && owner.recipient().equals(p.unsigned().recipient()), "Cardano payload differs from exact owned payment");
        else require(op.kind() == Kind.FUNDING && op.reference().matches("cardano-gas:[0-9a-f]{64}") && "ADA".equals(p.symbol())
                && "lovelace".equals(p.unsigned().unit()) && p.customerFee().signum() == 0 && p.decimals() == 6, "Cardano Gas funding domain differs");
    }
    private Owner lockOwner(Operation op) {
        if (op.kind() == Kind.FUNDING) {
            intent(op).ifPresent(request -> requests.lock(op.tenantId(), request.id()).orElseThrow()); return null;
        }
        if (op.kind() == Kind.WITHDRAWAL) {
            var row = orders.lockForSettlement(op.tenantId(), "ADA", op.reference()).orElseThrow();
            return new Owner(row.getStatus(), row.getFromAddress(), row.getToAddress(), row.getAssetSymbol(), row.getAmount(), row.getFee(), row.getTxHash());
        }
        var row = collections.lockForSubmission(op.tenantId(), "ADA", op.reference()).orElseThrow();
        return new Owner(row.getStatus(), row.getFromAddress(), row.getToAddress(), row.getAssetSymbol(), row.getAmount(), BigDecimal.ZERO, row.getTxHash());
    }
    private static Sync.AnyChainBlock block(JsonNode proof) {
        try { return Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(proof.path("blockBase64").asText())); }
        catch (Exception e) { throw new IllegalStateException("invalid original Cardano block proof", e); }
    }
    private static Query.ReadUtxosResponse unspent(JsonNode proof) {
        try { return Query.ReadUtxosResponse.parseFrom(Base64.getDecoder().decode(proof.path("unspentBase64").asText())); }
        catch (Exception e) { throw new IllegalStateException("invalid original Cardano unspent proof", e); }
    }
    private static BigDecimal lovelace(java.math.BigInteger amount) { return new BigDecimal(amount).movePointLeft(6); }
    private record Owner(String state, String sender, String recipient, String symbol, BigDecimal amount, BigDecimal fee, String hash) { }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

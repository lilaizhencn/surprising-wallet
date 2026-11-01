package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.MoneroTransactionRecord;
import com.surprising.wallet.chain.monero.MoneroSignedPayment;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.MoneroMpcResultVerifier;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Owned native payment admission, original encrypted artifact selection, relay barrier and atomic final settlement. */
@Service
@RequiredArgsConstructor
public class MoneroSubmissionService {
    private static final String CHAIN="XMR",SLOT="transaction";
    private static final HexFormat HEX=HexFormat.of();
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final MoneroTransactionRepository transactions;
    private final WalletMpcSignRequestRepository requests;
    private final WalletMpcKeyEpochRepository epochs;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final ChainScanHeightRepository heights;
    private final ChainAssetRepository assets;
    private final WithdrawalSettlementService settlements;
    private final CustodyGasAccountingService accounting;
    private final ObjectMapper json;
    private final PlatformTransactionManager manager;

    public enum Kind {WITHDRAWAL,COLLECTION,FUNDING}
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null || reference.isBlank())throw new IllegalArgumentException("Monero business reference required");}
    }
    public record Payment(String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation op){return requests.findBusiness(op.tenantId(),ChainType.XMR,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT);}
    public Optional<MoneroTransactionRecord> stored(Operation op){return intent(op).flatMap(request->transactions.find(op.tenantId(),request.id()));}
    public boolean terminal(Operation op){return stored(op).map(row->row.status().equals("CONFIRMED")).orElse(false);}
    public List<Operation> pending() {
        return transactions.pending(20).stream().map(row->requests.find(row.tenantId(),row.requestId()).orElseThrow()).map(request->
                new Operation(request.command().tenantId(),request.command().businessReference(),Kind.valueOf(request.command().businessType().name()))).toList();
    }
    public void refreshed(Operation op){intent(op).ifPresent(request->transactions.touchPending(op.tenantId(),request.id()));}
    public MoneroSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.XMR && request.command().payloadEncoding().equals(MoneroSigningPayload.ENCODING),"Monero payment encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),MoneroSigningPayload.class);
        require(payload.preimageHex(json).equals(request.command().preimageHex()),"Monero fixed native policy differs");return payload;
    }
    public Payment payment(Operation op){return transaction(()->{var owner=Objects.requireNonNull(lockOwner(op));return new Payment(owner.recipient(),owner.amount(),owner.fee());});}

    public MpcSignRequest reserve(Operation op,ChainAddressRecord source,MoneroSigningPayload draft) {
        return transaction(()->{
            var owner=lockOwner(op);var previous=intent(op);
            if(previous.isPresent()) {
                require(previous.get().command().keyId().equals(source.getMpcKeyId()),"Monero original source key changed");
                validate(op,owner,payload(previous.get()));return previous.get();
            }
            require(op.tenantId().equals(source.getTenantId()) && CHAIN.equals(source.getChain())
                    && (owner==null || owner.sender().equals(source.getAddress())) && draft.sender().equals(source.getAddress()),"Monero source differs from the owned payment");
            require(owner==null || owner.hash()==null && owner.state().equals("SIGNING"),"Monero business is not available for native signing");
            validate(op,owner,draft);var stored=addresses.findOwned(op.tenantId(),source.getId()).orElseThrow();
            require(Boolean.TRUE.equals(stored.getEnabled()) && Objects.equals(stored.getMpcKeyId(),source.getMpcKeyId())
                    && stored.getAddress().equals(source.getAddress()),"Monero source is not the published owned address");
            var key=signing.lockActiveKey(op.tenantId(),source.getMpcKeyId(),ChainType.XMR);
            var profile=(op.kind()==Kind.FUNDING?profiles.findByChainAndNetwork(CHAIN,draft.network()):profiles.findAccountByChain(CHAIN)).orElseThrow();
            var creation=json.readTree(HEX.parseHex(epochs.find(op.tenantId(),key.id(),key.activeEpoch()).orElseThrow().request().messageHex()));
            require(key.userId()==stored.getUserId() && key.biz()==stored.getBiz() && key.addressIndex()==stored.getAddressIndex()
                    && key.algorithm().equals("MONERO_NATIVE_2_OF_3") && draft.network().equals(profile.getNetwork())
                    && draft.network().equals(creation.path("network").asText()) && draft.genesisHash().equals(creation.path("genesisHash").asText())
                    && MoneroPublicAddressCodec.encode(HEX.parseHex(key.publicKeyHex()),draft.network(),true).equals(draft.sender()),"Monero native joint key or original network differs");
            require(draft.requiredConfirmations()==Math.max(1,profile.getWithdrawConfirmations()),"Monero confirmation policy changed before admission");
            require(heights.nativeState(CHAIN,MoneroScanAnchorService.SCANNER,false).orElseThrow().genesisHash().equals(draft.genesisHash()),"Monero payment differs from its address publication network");
            require(assets.findActiveDefinition(CHAIN,CHAIN).orElseThrow().getDecimals()==12,"Monero native precision must be twelve");
            if(op.kind()!=Kind.WITHDRAWAL)require(addresses.findEnabledByTenantAndAddress(op.tenantId(),CHAIN,draft.recipient()).orElseThrow().getMpcKeyId()!=null,"Monero internal recipient has no owned native key");
            require(!transactions.hasPendingSender(op.tenantId(),draft.sender()),"Monero source already has an unresolved native payment");
            var request=signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.XMR,
                    MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT,MpcSignCommand.Purpose.TRANSACTION,MoneroSigningPayload.ENCODING,
                    HEX.formatHex(json.writeValueAsBytes(draft)),draft.preimageHex(json)),nodes.online());
            require(transactions.insertPending(op.tenantId(),request.id(),draft.sender())==1,"Monero native admission raced");
            if(op.kind()==Kind.FUNDING)require(accounting.reservePreparation(op.tenantId(),request.id(),draft.sender(),draft.amount().add(xmr(draft.maximumFeeAtomic()))),
                    "Monero funding requires the owned tenant Gas account");
            return request;
        });
    }
    public void sign(Operation op,UUID requestId){outside();require(intent(op).orElseThrow().id().equals(requestId),"Monero signing request differs from its owner");signing.awaitSignature(op.tenantId(),requestId,nodes);}
    public MoneroSignedPayment commit(Operation op,UUID requestId) {
        return transaction(()->{
            var owner=lockOwner(op);var request=intent(op).orElseThrow();require(request.id().equals(requestId),"Monero selected request differs from its owner");
            validate(op,owner,payload(request));var selected=signing.selectedMonero(op.tenantId(),requestId);var row=transactions.find(op.tenantId(),requestId).orElseThrow();
            if(row.txHash()!=null){validateSelection(row,selected);require(owner==null || selected.transactionHash().equals(owner.hash()),"Monero selected business hash changed");return selected;}
            require(owner==null || Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()),"Monero business cannot bind a native selection");
            require(transactions.bindSigned(op.tenantId(),requestId,selected)==1,"Monero original artifact selection raced");
            if(owner!=null)require((op.kind()==Kind.WITHDRAWAL?orders.bindPrepared(op.tenantId(),CHAIN,op.reference(),selected.transactionHash())
                    :collections.bindPrepared(op.tenantId(),CHAIN,op.reference(),selected.transactionHash()))==1,"Monero original business hash binding raced");
            return selected;
        });
    }

    /** The business hash and selected artifact must already be committed before entering this RPC. */
    public String relay(Operation op) {
        outside();var selected=transaction(()->{
            var owner=lockOwner(op);var request=intent(op).orElseThrow();validate(op,owner,payload(request));
            var result=signing.selectedMonero(op.tenantId(),request.id());validateSelection(transactions.find(op.tenantId(),request.id()).orElseThrow(),result);
            require(owner==null || result.transactionHash().equals(owner.hash()),"Monero relay has no durable owned business hash");return result;
        });
        if(!terminal(op)) {
            signing.relayMonero(op.tenantId(),intent(op).orElseThrow().id(),selected.transactionHash(),nodes);
            restoreSent(op,selected.transactionHash());
        }
        return selected.transactionHash();
    }
    public void restoreSent(Operation op,String hash) {
        transaction(()->{var owner=lockOwner(op);var request=intent(op).orElseThrow();var selected=signing.selectedMonero(op.tenantId(),request.id());
            validateSelection(transactions.find(op.tenantId(),request.id()).orElseThrow(),selected);
            require(hash.equals(selected.transactionHash()) && (owner==null || hash.equals(owner.hash())),"Monero recovered hash differs from the original selection");
            restoreOwner(op,owner,hash);transactions.markSubmitted(op.tenantId(),request.id());return null;});
    }
    public void unknown(Operation op,String reason) {
        transaction(()->{var owner=lockOwner(op);if(owner!=null && owner.state().equals("SIGNING")) {
            if(op.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(op.tenantId(),CHAIN,op.reference(),owner.sender(),reason);
            else collections.markSigningUnknown(op.tenantId(),CHAIN,op.reference(),reason);
        }return null;});
    }
    public int releaseUnsigned(Operation op) {
        return transaction(()->{var owner=Objects.requireNonNull(lockOwner(op));if(owner.hash()!=null || intent(op).isPresent())return 0;
            return op.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(op.tenantId(),CHAIN,op.reference()):collections.recoverUnsignedDurable(op.tenantId(),CHAIN,op.reference());});
    }
    public boolean settle(Operation op,MpcSigningService.MoneroSnapshot snapshot) {
        return transaction(()->{
            var owner=lockOwner(op);var request=intent(op).orElseThrow();var p=payload(request);validate(op,owner,p);
            var selected=signing.selectedMonero(op.tenantId(),request.id());var row=transactions.find(op.tenantId(),request.id()).orElseThrow();validateSelection(row,selected);
            require(owner==null || selected.transactionHash().equals(owner.hash()),"Monero confirmation differs from its owned business hash");
            if(row.status().equals("CONFIRMED"))return false;
            MoneroMpcResultVerifier.observation(snapshot.request(),snapshot.result());
            require(snapshot.request().tenantId().equals(op.tenantId()) && snapshot.request().keyId().equals(request.command().keyId())
                    && snapshot.request().epoch()==request.command().keyEpoch() && snapshot.result().publicKeyHex().equals(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex())
                    && snapshot.result().publicAddress().equals(p.sender()),"Monero confirmation changed its original native signer");
            require(epochs.find(op.tenantId(),request.command().keyId(),request.command().keyEpoch()).orElseThrow().request().online()
                    .containsAll(snapshot.request().online()),"Monero confirmation uses unregistered native holders");
            var policy=json.readTree(HEX.parseHex(snapshot.request().messageHex()));var tx=snapshot.result().monero().path("transaction");
            require(policy.path("network").asText().equals(p.network()) && policy.path("genesisHash").asText().equals(p.genesisHash())
                    && policy.path("transactionHash").asText().equals(selected.transactionHash()),"Monero confirmation policy differs from the original payment");
            if(!tx.path("known").asBoolean() || tx.path("inPool").asBoolean() || tx.path("doubleSpendSeen").asBoolean())return false;
            require(!tx.path("coinbase").asBoolean() && tx.path("unlockTime").asLong()==0,"Monero native payment confirmation has a different transaction kind");
            long confirmations=tx.path("confirmations").asLong();if(confirmations<p.requiredConfirmations())return false;
            restoreOwner(op,owner,selected.transactionHash());
            require(transactions.confirm(op.tenantId(),request.id(),json.writeValueAsString(snapshot),tx.path("blockHeight").asLong(),tx.path("blockHash").asText(),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE,confirmations)))==1,"Monero canonical receipt recording raced");
            if(op.kind()==Kind.FUNDING) {
                // The funded principal remains inside this tenant's wallets; only the actual network fee is an expense.
                accounting.settlePreparation(op.tenantId(),request.id(),xmr(selected.feeAtomic()),selected.transactionHash());return true;
            }
            if(op.kind()==Kind.WITHDRAWAL) {
                require(settlements.confirm(op.tenantId(),CHAIN,op.reference(),selected.transactionHash()),"Monero withdrawal settlement changed before recording its canonical receipt");
                return true;
            }
            require(collections.updateAmounts(op.tenantId(),CHAIN,op.reference(),owner.amount(),xmr(selected.feeAtomic()))==1,"Monero collection fee recording raced");
            require(collections.markConfirmed(op.tenantId(),CHAIN,op.reference(),selected.transactionHash())==1,"Monero collection confirmation raced");return true;
        });
    }

    /** An owned incoming payment must match its original business; only customer withdrawals are credited again. */
    public boolean customerTransfer(UUID tenant,String hash,String source,String recipient) {
        var row=transactions.findByHash(tenant,hash).orElseThrow(()->new IllegalStateException("Monero owned incoming transaction has no durable native intent"));
        var request=requests.find(tenant,row.requestId()).orElseThrow();var p=payload(request);
        validateSelection(row,signing.selectedMonero(tenant,request.id()));
        require(p.sender().equals(source) && p.recipient().equals(recipient),"Monero incoming owner differs from its original payment");
        return request.command().businessType()==MpcSignCommand.BusinessType.WITHDRAWAL;
    }
    private void restoreOwner(Operation op,Owner owner,String hash) {
        if(owner!=null && Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()))require((op.kind()==Kind.WITHDRAWAL
                ?orders.recoverPreparedSent(op.tenantId(),CHAIN,op.reference(),hash):collections.recoverPreparedSent(op.tenantId(),CHAIN,op.reference(),hash))==1,"Monero SENT recovery raced");
    }
    private void validate(Operation op,Owner owner,MoneroSigningPayload p) {
        if(owner!=null)require(CHAIN.equals(owner.symbol()) && owner.sender().equals(p.sender()) && owner.recipient().equals(p.recipient())
                && owner.amount().compareTo(p.amount())==0 && owner.fee().compareTo(p.customerFee())==0,"Monero immutable policy differs from its owned payment");
        else require(op.kind()==Kind.FUNDING && op.reference().matches("xmr-gas:[0-9a-f]{64}") && p.customerFee().signum()==0,"Monero Gas funding domain differs");
    }
    private static void validateSelection(MoneroTransactionRecord row,MoneroSignedPayment selected) {
        require(selected.transactionHash().equals(row.txHash()) && selected.signSessionId().equals(row.selectedAttemptId())
                && selected.artifactHash().equals(row.artifactHash()) && selected.feeAtomic().equals(row.feeAtomic()),"Monero durable selection differs from its original authenticated artifact");
    }
    private Owner lockOwner(Operation op) {
        if(op.kind()==Kind.FUNDING){intent(op).ifPresent(request->requests.lock(op.tenantId(),request.id()).orElseThrow());return null;}
        if(op.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(op.tenantId(),CHAIN,op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}
        var row=collections.lockForSubmission(op.tenantId(),CHAIN,op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());
    }
    private <T>T transaction(Supplier<T> work){var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx.execute(status->work.get());}
    private static BigDecimal xmr(java.math.BigInteger amount){return new BigDecimal(amount).movePointLeft(12);}
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void outside(){require(!TransactionSynchronizationManager.isActualTransactionActive(),"Monero remote operations require a committed caller transaction");}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}

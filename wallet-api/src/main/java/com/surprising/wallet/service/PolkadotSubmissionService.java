package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.common.signing.PolkadotSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.polkadot.*;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;

/** Owned per-role nonce and immutable intent -> remote MPC -> durable wire -> finalized settlement. */
@Service
@RequiredArgsConstructor
public class PolkadotSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="POLKADOT_SCALE_PAYMENT_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final PolkadotTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    private final CustodyGasAccountingService accounting;

    public enum Kind { WITHDRAWAL,COLLECTION,FUNDING }
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("DOT business reference required");}
    }
    public record Payment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation op){return requests.findBusiness(op.tenantId(),ChainType.DOT,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT);}
    public String publicKey(ChainAddressRecord source){return signing.requireKey(source.getTenantId(),source.getMpcKeyId()).publicKeyHex();}
    public PolkadotSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.DOT && ENCODING.equals(request.command().payloadEncoding()),"DOT intent encoding differs");
        var p=json.readValue(HEX.parseHex(request.command().payloadHex()),PolkadotSigningPayload.class);
        require(HEX.formatHex(MpcPolkadotTransactionCodec.signingBytes(p.unsigned())).equals(request.command().preimageHex()),"DOT immutable signing message differs");return p;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Payment payment(Operation op){var owner=Objects.requireNonNull(lockOwner(op));return new Payment(owner.symbol(),owner.recipient(),owner.amount(),owner.fee());}

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation op,ChainAddressRecord source,PolkadotSigningPayload draft) {
        var owner=lockOwner(op);
        require(op.tenantId().equals(source.getTenantId()) && "DOT".equals(source.getChain())
                && (owner==null || owner.sender().equals(source.getAddress())),"DOT source differs from owned business");
        var stored=addresses.findOwned(op.tenantId(),source.getId()).orElseThrow();
        require(Boolean.TRUE.equals(stored.getEnabled()) && Objects.equals(stored.getMpcKeyId(),source.getMpcKeyId())
                && Objects.equals(stored.getAddress(),source.getAddress()),"DOT source is not the published owned address");
        var key=signing.lockActiveKey(op.tenantId(),source.getMpcKeyId(),ChainType.DOT);
        var previous=intent(op);
        if(previous.isPresent()){require(previous.get().command().keyId().equals(key.id()),"DOT source key changed");validate(op,owner,payload(previous.get()));return previous.get();}
        require(owner==null || (owner.hash()==null && "SIGNING".equals(owner.state())),"DOT owner is not available for signing");validate(op,owner,draft);
        var profile=profiles.findAccountByChain("DOT").orElseThrow();var u=draft.unsigned();
        require(key.userId()==stored.getUserId() && key.biz()==stored.getBiz() && key.addressIndex()==stored.getAddressIndex()
                && key.publicKeyHex().equals(u.publicKeyHex()) && stored.getAddress().equals(u.sender())
                && MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex())).equals(u.sender())
                && profile.getNetwork().equals(u.network()),"DOT signer differs from published network joint key");
        int nativeDecimals=assets.findActiveDefinition("DOT","DOT").orElseThrow().getDecimals();
        require(draft.nativeDecimals()==nativeDecimals,"DOT native precision differs from configured asset");
        if("DOT".equals(draft.symbol()))require(draft.decimals()==nativeDecimals && u.assetId()==null
                && u.role()==Role.ASSET_HUB,"DOT native role or precision differs");
        else {var token=tokens.findDefinition("DOT",profile.getNetwork(),draft.symbol()).orElseThrow();require(draft.decimals()==token.getDecimals()
                && token.getContractAddress().equals(u.assetId()) && u.role()==Role.ASSET_HUB,"DOT configured asset id or precision differs");}
        if(op.kind()==Kind.COLLECTION || op.kind()==Kind.FUNDING)require(addresses.findEnabledByTenantAndAddress(op.tenantId(),"DOT",u.recipient()).orElseThrow().getMpcKeyId()!=null,"DOT target has no owned joint key");
        require(!transactions.hasPendingSender(u.role().name(),u.sender()),"DOT sender already has a pending payment in this role");
        // The same AccountId32 has independent nonces in relay and Asset Hub.
        long nonce=sequences.reserve("DOT",u.role().name()+":"+u.sender(),u.nonce(),MpcSignCommand.reservationOwner(op.tenantId(),MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference()));
        var fixed=new PolkadotSigningPayload(draft.symbol(),draft.amount(),draft.customerFee(),draft.decimals(),draft.nativeDecimals(),draft.feeBudget(),draft.existentialDeposit(),
                new MpcPolkadotTransactionCodec.Unsigned(u.network(),u.role(),u.genesisHash(),u.specVersion(),u.transactionVersion(),u.sender(),u.publicKeyHex(),u.recipient(),u.amountAtomic(),u.assetId(),nonce,u.eraPeriod(),u.blockHeight(),u.blockHash(),u.extensions(),u.callIndices(),u.keepAlive()));
        var request=signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.DOT,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT,
                MpcSignCommand.Purpose.TRANSACTION,ENCODING,HEX.formatHex(json.writeValueAsBytes(fixed)),HEX.formatHex(MpcPolkadotTransactionCodec.signingBytes(fixed.unsigned()))),nodes.online());
        require(transactions.insertPending(request.id(),op.tenantId(),u.role().name(),u.sender(),nativeDecimals)==1,"DOT pending request admission raced");
        if(op.kind()==Kind.FUNDING)require(accounting.reservePreparation(op.tenantId(),request.id(),u.sender(),
                fixed.amount().add(new BigDecimal(fixed.feeBudget()).movePointLeft(nativeDecimals))),"DOT Gas funding requires a funded owned tenant Gas account");
        return request;
    }
    public void sign(Operation op,UUID id){require(intent(op).orElseThrow().id().equals(id),"DOT signature request differs from owner");signing.awaitSignature(op.tenantId(),id,nodes);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcPolkadotTransactionCodec.Signed commit(Operation op,UUID id) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();require(request.id().equals(id),"DOT selected request differs from owner");
        var p=payload(request);validate(op,owner,p);var signed=selected(op,request);var row=transactions.find(op.tenantId(),id).orElseThrow();
        if(row.txHash()!=null){require(row.txHash().equals(signed.txHash()) && signed.extrinsicHex().equals(row.rawPayload())
                && (owner==null || signed.txHash().equals(owner.hash())),"DOT durable raw/hash differs from selected witness");return signed;}
        require(owner==null || Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()),"DOT owner cannot bind raw");
        require(transactions.bindSigned(op.tenantId(),id,signed.txHash(),signed.extrinsicHex())==1,"DOT wire binding raced");
        if(owner!=null)require((op.kind()==Kind.WITHDRAWAL?orders.bindPrepared(op.tenantId(),"DOT",op.reference(),signed.txHash()):collections.bindPrepared(op.tenantId(),"DOT",op.reference(),signed.txHash()))==1,"DOT raw/hash business binding raced");return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation op,String hash){var owner=lockOwner(op);var request=intent(op).orElseThrow();require(selected(op,request).txHash().equals(hash) && (owner==null||hash.equals(owner.hash())),"DOT observed hash differs from owner");transactions.markSubmitted(op.tenantId(),request.id());}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation op,String hash) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();require(selected(op,request).txHash().equals(hash) && (owner==null||hash.equals(owner.hash())),"DOT recovery hash differs from owner");
        if(owner!=null && Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()))require((op.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(op.tenantId(),"DOT",op.reference(),hash):collections.recoverPreparedSent(op.tenantId(),"DOT",op.reference(),hash))==1,"DOT SENT recovery raced");
        transactions.markSubmitted(op.tenantId(),request.id());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation op,String reason){var owner=lockOwner(op);if(owner==null||!"SIGNING".equals(owner.state()))return;if(op.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(op.tenantId(),"DOT",op.reference(),owner.sender(),reason);else collections.markSigningUnknown(op.tenantId(),"DOT",op.reference(),reason);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation op){var owner=Objects.requireNonNull(lockOwner(op));if(owner.hash()!=null||intent(op).isPresent())return 0;return op.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(op.tenantId(),"DOT",op.reference()):collections.recoverUnsignedDurable(op.tenantId(),"DOT",op.reference());}
    public Optional<JsonNode> proof(Operation op){return intent(op).flatMap(r->transactions.find(op.tenantId(),r.id())).map(row->row.executionReceipt()).map(json::readTree);}
    public boolean terminal(Operation op){return intent(op).flatMap(r->transactions.find(op.tenantId(),r.id())).map(row->Set.of("CONFIRMED","FAILED").contains(row.status())).orElse(false);}
    public boolean paid(Operation op){return intent(op).flatMap(r->transactions.find(op.tenantId(),r.id())).map(row->"CONFIRMED".equals(row.status())).orElse(false);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation op,JsonNode receipt,long latest,int requiredConfirmations) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();var p=payload(request);validate(op,owner,p);var signed=selected(op,request);
        var row=transactions.find(op.tenantId(),request.id()).orElseThrow();
        require(signed.txHash().equals(row.txHash()) && signed.extrinsicHex().equals(row.rawPayload()) && (owner==null||signed.txHash().equals(owner.hash())),"DOT receipt has no selected owned durable wire");
        if(Set.of("CONFIRMED","FAILED").contains(row.status()))return false;
        require(owner==null||Set.of("SENT","CONFIRMING").contains(owner.state()),"DOT owner is not confirmable");
        var proof=row.executionReceipt()==null?Objects.requireNonNull(receipt):json.readTree(row.executionReceipt());
        var outcome=PolkadotExecutionReceipt.verify(proof,p.unsigned(),signed);if(latest<outcome.blockHeight())return false;long confirmations=latest-outcome.blockHeight()+1;
        boolean finalDepth=confirmations>=Math.max(1,requiredConfirmations);
        require(transactions.recordProof(op.tenantId(),request.id(),json.writeValueAsString(proof),outcome.feePlanck(),outcome.blockHeight(),finalDepth?(outcome.paid()?"CONFIRMED":"FAILED"):"CONFIRMING")==1,"DOT receipt recording raced");
        if(!finalDepth)return false;BigDecimal fee=new BigDecimal(outcome.feePlanck()).movePointLeft(p.nativeDecimals());
        if(owner==null){accounting.settlePreparation(op.tenantId(),request.id(),fee.add(outcome.paid()?p.amount():BigDecimal.ZERO),signed.txHash());return true;}
        if(op.kind()==Kind.WITHDRAWAL)return outcome.paid()?settlements.confirm(op.tenantId(),"DOT",op.reference(),signed.txHash()):settlements.fail(op.tenantId(),"DOT",op.reference(),signed.txHash(),"finalized Polkadot dispatch failure");
        require(collections.updateAmounts(op.tenantId(),"DOT",op.reference(),owner.amount(),fee)==1,"DOT collection actual fee update raced");
        require((outcome.paid()?collections.markConfirmed(op.tenantId(),"DOT",op.reference(),signed.txHash()):collections.updateStatus(op.tenantId(),"DOT",op.reference(),"FAILED",signed.txHash(),"finalized Polkadot dispatch failure",null))==1,"DOT collection settlement raced");return true;
    }
    private MpcPolkadotTransactionCodec.Signed selected(Operation op,MpcSignRequest request){require(request.state()==MpcSignRequest.State.COMPLETED,"DOT MPC result is incomplete");var p=payload(request);require(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"DOT selected joint key differs");return MpcPolkadotTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());}
    private void validate(Operation op,Owner owner,PolkadotSigningPayload p) {
        require(p!=null && p.amount()!=null && p.amount().signum()>0 && p.customerFee()!=null && p.decimals()>=0 && p.decimals()<=24
                && p.nativeDecimals()>=0 && p.nativeDecimals()<=24 && p.feeBudget()!=null && p.feeBudget().signum()>0
                && p.existentialDeposit()!=null && p.existentialDeposit().signum()>0 && (p.unsigned().assetId()!=null || p.unsigned().keepAlive())
                && p.unsigned().role()==Role.ASSET_HUB && p.amount().movePointRight(p.decimals()).toBigIntegerExact().equals(p.unsigned().amountAtomic()),"DOT exact amount, fee budget, minimum balance or keep-alive policy differs");
        if(owner!=null)require(owner.symbol().equals(p.symbol()) && owner.amount().compareTo(p.amount())==0 && owner.fee().compareTo(p.customerFee())==0
                && owner.sender().equals(p.unsigned().sender()) && owner.recipient().equals(p.unsigned().recipient()),"DOT payload differs from exact owned payment");
        else require(op.kind()==Kind.FUNDING && op.reference().matches("assethub-gas:[0-9a-f]{64}") && "DOT".equals(p.symbol())
                && p.customerFee().signum()==0 && p.unsigned().role()==Role.ASSET_HUB && p.unsigned().assetId()==null,"DOT funding domain differs");
    }
    private Owner lockOwner(Operation op){if(op.kind()==Kind.FUNDING){requests.findBusiness(op.tenantId(),ChainType.DOT,MpcSignCommand.BusinessType.FUNDING,op.reference(),SLOT).ifPresent(r->requests.lock(op.tenantId(),r.id()).orElseThrow());return null;}if(op.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(op.tenantId(),"DOT",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}var row=collections.lockForSubmission(op.tenantId(),"DOT",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());}
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

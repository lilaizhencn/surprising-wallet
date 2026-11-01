package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.NearTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;

/** Owned nonce admission and intent, remote MPC, durable selected wire, then atomic receipt settlement. */
@Service
@RequiredArgsConstructor
public class NearSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="NEAR_BORSH_PAYMENT_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final NearTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final TokenConfigRepository tokens;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    private final CustodyGasUsageRepository gasUsages;
    public enum Kind { WITHDRAWAL,COLLECTION }
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("NEAR business reference required");}
    }
    public record Payment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation op){return requests.findBusiness(op.tenantId(),ChainType.NEAR,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT);}
    public static String registrationReference(Operation op) {
        try{return "near-storage:"+HEX.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                (op.tenantId()+":"+op.kind()+":"+op.reference()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    public Optional<MpcSignRequest> registration(Operation op) {
        return requests.findBusiness(op.tenantId(),ChainType.NEAR,MpcSignCommand.BusinessType.FUNDING,registrationReference(op),"storage-registration");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Payment lockRegistrationOwner(Operation op,String sender,Payment expected) {
        var owner=lockOwner(op);
        require(owner.hash()==null && intent(op).isEmpty() && Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state())
                && owner.sender().equals(sender) && owner.symbol().equals(expected.symbol()) && owner.recipient().equals(expected.recipient())
                && owner.amount().compareTo(expected.amount())==0 && owner.fee().compareTo(expected.customerFee())==0,
                "NEP-145 parent differs from original unsigned business");return expected;
    }
    public String publicKey(ChainAddressRecord source){return signing.requireKey(source.getTenantId(),source.getMpcKeyId()).publicKeyHex();}
    public NearSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.NEAR && ENCODING.equals(request.command().payloadEncoding()),"NEAR intent encoding differs");
        var p=json.readValue(HEX.parseHex(request.command().payloadHex()),NearSigningPayload.class);
        require(HEX.formatHex(MpcNearTransactionCodec.signingBytes(p.unsigned())).equals(request.command().preimageHex()),"NEAR fixed signing message differs");return p;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Payment payment(Operation op){var owner=lockOwner(op);return new Payment(owner.symbol(),owner.recipient(),owner.amount(),owner.fee());}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation op,ChainAddressRecord source,NearSigningPayload draft) {
        var owner=lockOwner(op);
        require(op.tenantId().equals(source.getTenantId()) && "NEAR".equals(source.getChain()) && owner.sender().equals(source.getAddress()),"NEAR source differs from owned business");
        var previous=intent(op);
        if(previous.isPresent()){require(previous.get().command().keyId().equals(source.getMpcKeyId()),"NEAR source key changed");validate(owner,payload(previous.get()));return previous.get();}
        require(owner.hash()==null && ("SIGNING".equals(owner.state()) || ("BROADCAST_UNKNOWN".equals(owner.state()) && registration(op).isPresent())),"NEAR owner is not available for signing");validate(owner,draft);
        var stored=addresses.findOwned(op.tenantId(),source.getId()).orElseThrow();
        require((Boolean.TRUE.equals(stored.getEnabled()) || registration(op).isPresent()) && Objects.equals(stored.getMpcKeyId(),source.getMpcKeyId()) && Objects.equals(stored.getAddress(),source.getAddress()),"NEAR source is not the published owned address");
        var u=draft.unsigned();var profile=(registration(op).isPresent()?profiles.findByChainAndNetwork("NEAR",u.network()):profiles.findAccountByChain("NEAR")).orElseThrow();var key=signing.lockActiveKey(op.tenantId(),source.getMpcKeyId(),ChainType.NEAR);
        require(key.userId()==stored.getUserId() && key.biz()==stored.getBiz() && key.addressIndex()==stored.getAddressIndex()
                && key.publicKeyHex().equals(u.publicKeyHex()) && MpcNearTransactionCodec.address(HEX.parseHex(key.publicKeyHex())).equals(u.sender())
                && profile.getNetwork().equals(u.network()),"NEAR signer differs from published network joint key");
        if("NEAR".equals(owner.symbol()))require(draft.decimals()==24 && u.tokenContract()==null,"NEAR native asset or precision differs");
        else if(registration(op).isPresent())requireRegistrationReady(op,draft);
        else {var token=tokens.findDefinition("NEAR",profile.getNetwork(),owner.symbol()).orElseThrow();require(draft.decimals()==token.getDecimals() && token.getContractAddress().equals(u.tokenContract()),"NEAR configured contract or precision differs");}
        if(op.kind()==Kind.COLLECTION && registration(op).isEmpty())require(addresses.findEnabledByTenantAndAddress(op.tenantId(),"NEAR",u.recipient()).orElseThrow().getMpcKeyId()!=null,"NEAR collector has no owned joint key");
        // One unresolved sender intent across all assets avoids invalidating an older selected nonce.
        require(!transactions.hasPendingSender(u.sender()),"NEAR sender already has a pending payment intent");
        long nonce=sequences.reserve("NEAR",u.sender(),u.nonce());
        var fixed=new NearSigningPayload(draft.symbol(),draft.amount(),draft.customerFee(),draft.decimals(),
                new MpcNearTransactionCodec.Unsigned(u.network(),u.genesisHash(),u.sender(),nonce,u.recipient(),u.amountAtomic(),u.tokenContract(),u.gas(),u.memo(),u.blockHeight(),u.validUntilHeight(),u.blockHash(),u.publicKeyHex()));
        String digest=MpcNearTransactionCodec.transactionHash(fixed.unsigned());
        require(transactions.insertPending(record(owner,fixed,digest,"SIGNING",null,null,null,null,null))==1,"NEAR selected nonce reproduces an existing transaction");
        return signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.NEAR,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT,
                MpcSignCommand.Purpose.TRANSACTION,ENCODING,HEX.formatHex(json.writeValueAsBytes(fixed)),HEX.formatHex(MpcNearTransactionCodec.signingBytes(fixed.unsigned()))),nodes.online());
    }
    public void sign(Operation op,UUID id){require(intent(op).orElseThrow().id().equals(id),"NEAR signature request differs from owner");signing.awaitSignature(op.tenantId(),id,nodes);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcNearTransactionCodec.Signed commit(Operation op,UUID id) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();require(request.id().equals(id),"NEAR selected request differs from owner");
        var p=payload(request);validate(owner,p);var signed=selected(op,request);String raw=json.writeValueAsString(signed);var row=transactions.find(signed.txHash()).orElseThrow();
        if(owner.hash()!=null){require(owner.hash().equals(signed.txHash()) && raw.equals(row.getRawPayload()),"NEAR durable raw/hash differs from selected witness");return signed;}
        require("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state()),"NEAR owner cannot bind raw");
        require(transactions.bindSigned(signed.txHash(),raw)==1,"NEAR selected wire binding raced");
        require((op.kind()==Kind.WITHDRAWAL?orders.bindPrepared(op.tenantId(),"NEAR",op.reference(),signed.txHash()):collections.bindPrepared(op.tenantId(),"NEAR",op.reference(),signed.txHash()))==1,"NEAR raw/hash business binding raced");return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation op,String digest){var owner=lockOwner(op);require(digest.equals(owner.hash()) && intent(op).isPresent(),"NEAR observed digest differs from owner");transactions.markSubmitted(digest);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation op,String digest) {
        var owner=lockOwner(op);require(digest.equals(owner.hash()) && intent(op).isPresent(),"NEAR recovery digest differs from owner");if(!Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()))return;
        require((op.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(op.tenantId(),"NEAR",op.reference(),digest):collections.recoverPreparedSent(op.tenantId(),"NEAR",op.reference(),digest))==1,"NEAR SENT recovery raced");transactions.markSubmitted(digest);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation op,String reason){var owner=lockOwner(op);if(!"SIGNING".equals(owner.state()))return;if(op.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(op.tenantId(),"NEAR",op.reference(),owner.sender(),reason);else collections.markSigningUnknown(op.tenantId(),"NEAR",op.reference(),reason);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation op){var owner=lockOwner(op);if(owner.hash()!=null||intent(op).isPresent()||registration(op).isPresent())return 0;return op.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(op.tenantId(),"NEAR",op.reference()):collections.recoverUnsignedDurable(op.tenantId(),"NEAR",op.reference());}
    private void requireRegistrationReady(Operation op,NearSigningPayload draft) {
        var request=registration(op).orElseThrow();
        var p=NearRegistrationService.payload(op,request,json);
        require(request.state()==MpcSignRequest.State.COMPLETED,"NEP-145 signature is incomplete");
        var signed=MpcNearTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());
        require(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"NEP-145 payer key differs");
        var tx=transactions.find(signed.txHash()).orElseThrow();var usage=gasUsages.find(op.tenantId(),"ADDRESS_PREPARATION",request.id()).orElseThrow();
        require("CONFIRMED".equals(tx.getStatus()) && json.writeValueAsString(signed).equals(tx.getRawPayload())
                && "SETTLED".equals(usage.status()),"NEP-145 final receipt and Gas settlement must precede payment");
        var proof=NearStorageReceipt.verify(json.readValue(tx.getExecutionReceipt(),NearStorageReceipt.Proof.class),p.unsigned(),signed);
        require(proof.registered() && usage.actualAmount().movePointRight(24).toBigIntegerExact().equals(proof.expenseYocto())
                && p.parentSender().equals(draft.unsigned().sender()) && p.payment().symbol().equals(draft.symbol())
                && p.payment().amount().compareTo(draft.amount())==0 && p.payment().customerFee().compareTo(draft.customerFee())==0
                && p.payment().recipient().equals(draft.unsigned().recipient()) && p.unsigned().contract().equals(draft.unsigned().tokenContract())
                && p.tokenDecimals()==draft.decimals(),"NEAR payment differs from original completed registration");
    }
    public Optional<NearExecutionReceipt.Proof> proof(Operation op){return intent(op).flatMap(request->transactions.find(MpcNearTransactionCodec.transactionHash(payload(request).unsigned()))).map(NearTransactionRecord::getExecutionReceipt).map(raw->json.readValue(raw,NearExecutionReceipt.Proof.class));}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation op,NearExecutionReceipt.Proof receipt,long latest,int requiredConfirmations) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();var p=payload(request);validate(owner,p);var signed=selected(op,request);
        require(signed.txHash().equals(owner.hash()),"NEAR receipt differs from owned hash");var row=transactions.find(signed.txHash()).orElseThrow();
        require(json.writeValueAsString(signed).equals(row.getRawPayload()),"NEAR receipt has no selected durable wire");
        if(Set.of("CONFIRMED","FAILED").contains(owner.state()))return false;
        require(Set.of("SENT","CONFIRMING").contains(owner.state()),"NEAR owner is not confirmable");
        var proof=row.getExecutionReceipt()==null?Objects.requireNonNull(receipt):json.readValue(row.getExecutionReceipt(),NearExecutionReceipt.Proof.class);
        var verified=NearExecutionReceipt.verify(proof,p.unsigned(),signed);var outcome=verified.outcome();
        if(latest<verified.blockHeight())return false;long confirmations=latest-verified.blockHeight()+1;
        transactions.upsert(record(owner,p,signed.txHash(),confirmations>=Math.max(1,requiredConfirmations)?(outcome.paid()?"CONFIRMED":"FAILED"):"CONFIRMING",verified.blockHeight(),outcome.gasBurnt(),outcome.expenseYocto(),row.getRawPayload(),json.writeValueAsString(proof)));
        if(confirmations<Math.max(1,requiredConfirmations))return false;
        if(op.kind()==Kind.WITHDRAWAL)return outcome.paid()?settlements.confirm(op.tenantId(),"NEAR",op.reference(),signed.txHash()):settlements.fail(op.tenantId(),"NEAR",op.reference(),signed.txHash(),"final NEAR execution failure with completed refunds");
        require(collections.updateAmounts(op.tenantId(),"NEAR",op.reference(),owner.amount(),new BigDecimal(outcome.expenseYocto()).movePointLeft(24))==1,"NEAR collection fee update raced");
        require((outcome.paid()?collections.markConfirmed(op.tenantId(),"NEAR",op.reference(),signed.txHash()):collections.updateStatus(op.tenantId(),"NEAR",op.reference(),"FAILED",signed.txHash(),"final NEAR execution failure with completed refunds",null))==1,"NEAR collection settlement raced");return true;
    }
    private MpcNearTransactionCodec.Signed selected(Operation op,MpcSignRequest request){require(request.state()==MpcSignRequest.State.COMPLETED,"NEAR MPC result is not completed");var p=payload(request);require(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"NEAR selected joint key differs");return MpcNearTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());}
    private static void validate(Owner owner,NearSigningPayload p){require(p!=null && owner.amount().signum()>0 && owner.symbol().equals(p.symbol()) && owner.amount().compareTo(p.amount())==0 && owner.fee().compareTo(p.customerFee())==0
            && owner.sender().equals(p.unsigned().sender()) && owner.recipient().equals(p.unsigned().recipient())
            && p.decimals()>=0 && p.decimals()<=24 && owner.amount().movePointRight(p.decimals()).toBigIntegerExact().equals(p.unsigned().amountAtomic()),"NEAR payload differs from exact owned payment");
        require(("NEAR".equals(owner.symbol()) && p.decimals()==24 && p.unsigned().tokenContract()==null)||(!"NEAR".equals(owner.symbol()) && p.unsigned().tokenContract()!=null),"NEAR asset kind differs");}
    private Owner lockOwner(Operation op){if(op.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(op.tenantId(),"NEAR",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}var row=collections.lockForSubmission(op.tenantId(),"NEAR",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());}
    private static NearTransactionRecord record(Owner owner,NearSigningPayload p,String hash,String status,Long height,Long gas,java.math.BigInteger fee,String raw,String proof){return NearTransactionRecord.builder().chain("NEAR").txHash(hash).actionIndex(0L).sender(p.unsigned().sender()).receiver(p.unsigned().recipient()).assetSymbol(p.symbol()).amount(owner.amount()).gasBurnt(gas).feeYocto(fee).blockHeight(height).status(status).rawPayload(raw).executionReceipt(proof).build();}
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

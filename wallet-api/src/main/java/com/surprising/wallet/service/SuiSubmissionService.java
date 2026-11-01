package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.common.signing.SuiSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;

/** Owned Coin admission and intent, remote MPC, durable selected wire, then atomic receipt settlement. */
@Service
@RequiredArgsConstructor
public class SuiSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="SUI_PTB_PAYMENT_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final SuiTransactionRepository transactions;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final TokenConfigRepository tokens;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    public enum Kind { WITHDRAWAL,COLLECTION }
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("Sui business reference required");}
    }
    public record Payment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation op){return requests.findBusiness(op.tenantId(),ChainType.SUI,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT);}
    public String publicKey(ChainAddressRecord source){return signing.requireKey(source.getTenantId(),source.getMpcKeyId()).publicKeyHex();}
    public SuiSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.SUI && ENCODING.equals(request.command().payloadEncoding()),"Sui intent encoding differs");
        var p=json.readValue(HEX.parseHex(request.command().payloadHex()),SuiSigningPayload.class);
        require(HEX.formatHex(MpcSuiTransactionCodec.signingBytes(p.unsigned())).equals(request.command().preimageHex()),"Sui fixed signing message differs");return p;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Payment payment(Operation op){var owner=lockOwner(op);return new Payment(owner.symbol(),owner.recipient(),owner.amount(),owner.fee());}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation op,ChainAddressRecord source,SuiSigningPayload draft) {
        var owner=lockOwner(op);
        require(op.tenantId().equals(source.getTenantId()) && "SUI".equals(source.getChain()) && owner.sender().equals(source.getAddress()),"Sui source differs from owned business");
        var previous=intent(op);
        if(previous.isPresent()){require(previous.get().command().keyId().equals(source.getMpcKeyId()),"Sui source key changed");validate(owner,payload(previous.get()));return previous.get();}
        require(owner.hash()==null && "SIGNING".equals(owner.state()),"Sui owner is not available for signing");validate(owner,draft);
        var stored=addresses.findOwned(op.tenantId(),source.getId()).orElseThrow();
        require(Boolean.TRUE.equals(stored.getEnabled()) && Objects.equals(stored.getMpcKeyId(),source.getMpcKeyId()) && Objects.equals(stored.getAddress(),source.getAddress()),"Sui source is not the published owned address");
        var profile=profiles.findAccountByChain("SUI").orElseThrow();var key=signing.lockActiveKey(op.tenantId(),source.getMpcKeyId(),ChainType.SUI);var u=draft.unsigned();
        require(key.userId()==stored.getUserId() && key.biz()==stored.getBiz() && key.addressIndex()==stored.getAddressIndex()
                && key.publicKeyHex().equals(u.publicKeyHex()) && MpcSuiTransactionCodec.address(HEX.parseHex(key.publicKeyHex())).equals(u.sender())
                && profile.getNetwork().equals(u.network()),"Sui signer differs from published network joint key");
        if("SUI".equals(owner.symbol()))require(draft.decimals()==9 && MpcSuiTransactionCodec.canonicalCoinType(SuiRpcClient.SUI_COIN_TYPE).equals(u.coinType()),"Sui native source or precision differs");
        else {var token=tokens.findDefinition("SUI",profile.getNetwork(),owner.symbol()).orElseThrow();require(draft.decimals()==token.getDecimals() && MpcSuiTransactionCodec.canonicalCoinType(token.getContractAddress()).equals(u.coinType()),"Sui configured Coin type or precision differs");}
        if(op.kind()==Kind.COLLECTION)require(addresses.findEnabledByTenantAndAddress(op.tenantId(),"SUI",u.recipient()).orElseThrow().getMpcKeyId()!=null,"Sui collector has no owned joint key");
        // The active key lock serializes admission across all assets of this sender. No object reselection after this point.
        require(!transactions.hasPendingSender(u.sender()),"Sui sender already has a pending object intent");
        String digest=MpcSuiTransactionCodec.digest(u);
        require(transactions.insertPending(record(owner,draft,digest,"SIGNING",null,0,null,null))==1,"Sui selected objects reproduce an existing transaction; reconciliation required");
        return signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.SUI,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT,
                MpcSignCommand.Purpose.TRANSACTION,ENCODING,HEX.formatHex(json.writeValueAsBytes(draft)),HEX.formatHex(MpcSuiTransactionCodec.signingBytes(u))),nodes.online());
    }
    public void sign(Operation op,UUID id){require(intent(op).orElseThrow().id().equals(id),"Sui signature request differs from owner");signing.awaitSignature(op.tenantId(),id,nodes);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSuiTransactionCodec.Signed commit(Operation op,UUID id) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();require(request.id().equals(id),"Sui selected request differs from owner");
        var p=payload(request);validate(owner,p);var signed=selected(op,request);String raw=json.writeValueAsString(signed);var row=transactions.find(signed.digest()).orElseThrow();
        if(owner.hash()!=null){require(owner.hash().equals(signed.digest()) && raw.equals(row.getRawPayload()),"Sui durable raw/digest differs from selected witness");return signed;}
        require("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state()),"Sui owner cannot bind raw");
        require(transactions.bindSigned(signed.digest(),raw)==1,"Sui selected wire binding raced");
        require((op.kind()==Kind.WITHDRAWAL?orders.bindPrepared(op.tenantId(),"SUI",op.reference(),signed.digest()):collections.bindPrepared(op.tenantId(),"SUI",op.reference(),signed.digest()))==1,"Sui raw/digest business binding raced");return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation op,String digest){var owner=lockOwner(op);require(digest.equals(owner.hash()) && intent(op).isPresent(),"Sui observed digest differs from owner");transactions.markSubmitted(digest);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation op,String digest) {
        var owner=lockOwner(op);require(digest.equals(owner.hash()) && intent(op).isPresent(),"Sui recovery digest differs from owner");if(!Set.of("SIGNING","BROADCAST_UNKNOWN").contains(owner.state()))return;
        require((op.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(op.tenantId(),"SUI",op.reference(),digest):collections.recoverPreparedSent(op.tenantId(),"SUI",op.reference(),digest))==1,"Sui SENT recovery raced");transactions.markSubmitted(digest);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation op,String reason){var owner=lockOwner(op);if(!"SIGNING".equals(owner.state()))return;if(op.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(op.tenantId(),"SUI",op.reference(),owner.sender(),reason);else collections.markSigningUnknown(op.tenantId(),"SUI",op.reference(),reason);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation op){var owner=lockOwner(op);if(owner.hash()!=null||intent(op).isPresent())return 0;return op.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(op.tenantId(),"SUI",op.reference()):collections.recoverUnsignedDurable(op.tenantId(),"SUI",op.reference());}
    public Optional<JsonNode> proof(Operation op){return intent(op).flatMap(request->transactions.find(MpcSuiTransactionCodec.digest(payload(request).unsigned()))).map(SuiTransactionRecord::getExecutionReceipt).map(json::readTree);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation op,JsonNode receipt,long latest,int requiredConfirmations) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();var p=payload(request);validate(owner,p);var signed=selected(op,request);
        require(signed.digest().equals(owner.hash()),"Sui receipt differs from owned digest");var row=transactions.find(signed.digest()).orElseThrow();
        require(json.writeValueAsString(signed).equals(row.getRawPayload()),"Sui receipt has no selected durable wire");
        if(Set.of("CONFIRMED","FAILED").contains(owner.state()))return false;
        require(Set.of("SENT","CONFIRMING").contains(owner.state()),"Sui owner is not confirmable");
        var proof=row.getExecutionReceipt()==null?Objects.requireNonNull(receipt):json.readTree(row.getExecutionReceipt());
        var outcome=SuiExecutionReceipt.verify(proof,p.unsigned(),signed);
        if(latest<outcome.checkpoint())return false;long confirmations=latest-outcome.checkpoint()+1;
        transactions.upsert(record(owner,p,signed.digest(),confirmations>=Math.max(1,requiredConfirmations)?(outcome.paid()?"CONFIRMED":"FAILED"):"CONFIRMING",outcome.checkpoint(),outcome.feeMist(),row.getRawPayload(),json.writeValueAsString(proof)));
        if(confirmations<Math.max(1,requiredConfirmations))return false;
        if(op.kind()==Kind.WITHDRAWAL)return outcome.paid()?settlements.confirm(op.tenantId(),"SUI",op.reference(),signed.digest()):settlements.fail(op.tenantId(),"SUI",op.reference(),signed.digest(),"committed Sui execution failure");
        require(collections.updateAmounts(op.tenantId(),"SUI",op.reference(),owner.amount(),BigDecimal.valueOf(outcome.feeMist()).movePointLeft(9))==1,"Sui collection fee update raced");
        require((outcome.paid()?collections.markConfirmed(op.tenantId(),"SUI",op.reference(),signed.digest()):collections.updateStatus(op.tenantId(),"SUI",op.reference(),"FAILED",signed.digest(),"committed Sui execution failure",null))==1,"Sui collection settlement raced");return true;
    }
    private MpcSuiTransactionCodec.Signed selected(Operation op,MpcSignRequest request){require(request.state()==MpcSignRequest.State.COMPLETED,"Sui MPC result is not completed");var p=payload(request);require(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"Sui selected joint key differs");return MpcSuiTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());}
    private static void validate(Owner owner,SuiSigningPayload p){require(p!=null && owner.amount().signum()>0 && owner.symbol().equals(p.symbol()) && owner.amount().compareTo(p.amount())==0 && owner.fee().compareTo(p.customerFee())==0
            && MpcSuiTransactionCodec.canonicalAddress(owner.sender()).equals(p.unsigned().sender()) && MpcSuiTransactionCodec.canonicalAddress(owner.recipient()).equals(p.unsigned().recipient())
            && p.decimals()>=0 && p.decimals()<=18 && owner.amount().movePointRight(p.decimals()).longValueExact()==p.unsigned().amountAtomic(),"Sui payload differs from exact owned payment");}
    private Owner lockOwner(Operation op){if(op.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(op.tenantId(),"SUI",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}var row=collections.lockForSubmission(op.tenantId(),"SUI",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());}
    private static SuiTransactionRecord record(Owner owner,SuiSigningPayload p,String digest,String status,Long checkpoint,long fee,String raw,String proof){return SuiTransactionRecord.builder().chain("SUI").txDigest(digest).sender(p.unsigned().sender()).receiver(p.unsigned().recipient()).assetSymbol(p.symbol()).coinType(p.unsigned().coinType()).amount(owner.amount()).feeMist(fee).checkpoint(checkpoint).status(status).rawPayload(raw).executionReceipt(proof).build();}
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

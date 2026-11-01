package com.surprising.wallet.service;

import com.surprising.wallet.common.signing.AptosSigningPayload;
import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.aptos.*;
import com.surprising.wallet.chain.model.AptosTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;

/** Owned sequence/intent transaction, remote MPC, selected raw/hash transaction, then network. */
@Service
@RequiredArgsConstructor
public class AptosSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="APTOS_TX_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final AptosTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
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
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("Aptos business reference required");}
    }
    public record Payment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation op){return requests.findBusiness(op.tenantId(),ChainType.APTOS,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT);}
    public String publicKey(ChainAddressRecord source){return signing.requireKey(source.getTenantId(),source.getMpcKeyId()).publicKeyHex();}
    public AptosSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.APTOS && ENCODING.equals(request.command().payloadEncoding()),"Aptos intent encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),AptosSigningPayload.class);
        require(HEX.formatHex(MpcAptosTransactionCodec.signingBytes(payload.unsigned())).equals(request.command().preimageHex()),"Aptos fixed signing message differs");return payload;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Payment payment(Operation op){var owner=lockOwner(op);return new Payment(owner.symbol(),owner.recipient(),owner.amount(),owner.fee());}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation op,ChainAddressRecord source,AptosSigningPayload draft) {
        var owner=lockOwner(op);require(op.tenantId().equals(source.getTenantId()) && "APTOS".equals(source.getChain()) && owner.sender().equals(source.getAddress()),"Aptos source differs from owned business");
        var previous=intent(op);if(previous.isPresent()){require(previous.get().command().keyId().equals(source.getMpcKeyId()),"Aptos source key changed");validate(owner,payload(previous.get()));return previous.get();}
        require(owner.hash()==null && "SIGNING".equals(owner.state()),"Aptos owner is not available for signing");validate(owner,draft);
        var profile=profiles.findAccountByChain("APTOS").orElseThrow();var key=signing.lockActiveKey(op.tenantId(),source.getMpcKeyId(),ChainType.APTOS);
        require(source.getAddress().equals(MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex()))) && key.userId()==source.getUserId() && key.biz()==source.getBiz() && key.addressIndex()==source.getAddressIndex()
                && key.publicKeyHex().equals(draft.unsigned().publicKeyHex()),"Aptos signer differs from owned joint key");
        require(profile.getChainId()!=null && profile.getChainId().intValue()==draft.unsigned().chainId(),"Aptos signing network differs from enabled profile");
        if("APT".equals(owner.symbol()))require(draft.decimals()==8 && draft.unsigned().metadata()==null,"Aptos native asset differs");
        else {var token=tokens.findDefinition("APTOS",profile.getNetwork(),owner.symbol()).orElseThrow();require(draft.decimals()==token.getDecimals()
                    && MpcAptosTransactionCodec.canonicalAddress(AptosFungibleAsset.requireMetadata(token)).equals(draft.unsigned().metadata()),"Aptos metadata or precision differs from enabled asset");}
        if(op.kind()==Kind.COLLECTION)require(addresses.findEnabledByTenantAndAddress(op.tenantId(),"APTOS",draft.unsigned().recipient()).orElseThrow().getMpcKeyId()!=null,"Aptos collector has no tenant joint key");
        var u=draft.unsigned();long sequence=sequences.reserve("APTOS",source.getAddress(),u.sequence(),MpcSignCommand.reservationOwner(op.tenantId(),MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference()));
        var fixed=new AptosSigningPayload(draft.symbol(),draft.amount(),draft.customerFee(),draft.decimals(),new MpcAptosTransactionCodec.Unsigned(u.sender(),sequence,u.recipient(),u.amountAtomic(),u.metadata(),u.maxGasAmount(),u.gasUnitPrice(),u.expiration(),u.chainId(),u.publicKeyHex()));
        return signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.APTOS,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),SLOT,
                MpcSignCommand.Purpose.TRANSACTION,ENCODING,HEX.formatHex(json.writeValueAsBytes(fixed)),HEX.formatHex(MpcAptosTransactionCodec.signingBytes(fixed.unsigned()))),nodes.online());
    }
    public void sign(Operation op,UUID requestId){require(intent(op).orElseThrow().id().equals(requestId),"Aptos signature request differs from owner");signing.awaitSignature(op.tenantId(),requestId,nodes);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcAptosTransactionCodec.Signed commit(Operation op,UUID requestId) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();require(request.id().equals(requestId),"Aptos selected request differs from owner");var payload=payload(request);validate(owner,payload);var signed=selected(op,request);
        if(owner.hash()!=null){require(owner.hash().equals(signed.txId()) && transactions.findSigned(signed.txId()).orElseThrow().getRawPayload().equals(signed.json()),"Aptos durable raw/hash differs from selected signature");return signed;}
        require("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state()),"Aptos owner cannot bind raw");
        transactions.upsert(record(owner,payload,signed,"PREPARED",null,0,0,0));
        require((op.kind()==Kind.WITHDRAWAL?orders.bindPrepared(op.tenantId(),"APTOS",op.reference(),signed.txId()):collections.bindPrepared(op.tenantId(),"APTOS",op.reference(),signed.txId()))==1,"Aptos raw/hash binding raced");return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation op,String hash){var owner=lockOwner(op);require(hash.equals(owner.hash()) && intent(op).isPresent(),"Aptos observed hash differs from owner");transactions.markSubmitted(hash);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation op,String hash) {
        var owner=lockOwner(op);require(hash.equals(owner.hash()) && intent(op).isPresent(),"Aptos recovery differs from owner");if(!("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state())))return;
        require((op.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(op.tenantId(),"APTOS",op.reference(),hash):collections.recoverPreparedSent(op.tenantId(),"APTOS",op.reference(),hash))==1,"Aptos SENT recovery raced");transactions.markSubmitted(hash);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation op,String reason){var owner=lockOwner(op);if(!"SIGNING".equals(owner.state()))return;if(op.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(op.tenantId(),"APTOS",op.reference(),owner.sender(),reason);else collections.markSigningUnknown(op.tenantId(),"APTOS",op.reference(),reason);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation op){var owner=lockOwner(op);if(owner.hash()!=null||intent(op).isPresent())return 0;return op.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(op.tenantId(),"APTOS",op.reference()):collections.recoverUnsignedDurable(op.tenantId(),"APTOS",op.reference());}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation op,JsonNode receipt,long latestVersion,int requiredConfirmations) {
        var owner=lockOwner(op);var request=intent(op).orElseThrow();var payload=payload(request);validate(owner,payload);var signed=selected(op,request);require(signed.txId().equals(owner.hash()),"Aptos receipt differs from owned hash");
        var outcome=AptosExecutionReceipt.verify(receipt,payload.unsigned(),signed);if(latestVersion<outcome.version())return false;
        long confirmations=latestVersion-outcome.version()+1;if(confirmations<Math.max(1,requiredConfirmations))return false;
        if("CONFIRMED".equals(owner.state())||"FAILED".equals(owner.state()))return false;require("SENT".equals(owner.state())||"CONFIRMING".equals(owner.state()),"Aptos owner is not confirmable");
        require(transactions.findSigned(signed.txId()).isPresent(),"Aptos receipt has no durable raw");transactions.upsert(record(owner,payload,signed,outcome.paid()?"CONFIRMED":"FAILED",outcome.version(),(int)Math.min(Integer.MAX_VALUE,confirmations),outcome.gasUsed(),outcome.feeOctas()));
        if(op.kind()==Kind.WITHDRAWAL)return outcome.paid()?settlements.confirm(op.tenantId(),"APTOS",op.reference(),signed.txId()):settlements.fail(op.tenantId(),"APTOS",op.reference(),signed.txId(),"committed Aptos execution failure");
        require(collections.updateAmounts(op.tenantId(),"APTOS",op.reference(),owner.amount(),BigDecimal.valueOf(outcome.feeOctas()).movePointLeft(8))==1,"Aptos collection fee update raced");
        require((outcome.paid()?collections.markConfirmed(op.tenantId(),"APTOS",op.reference(),signed.txId()):collections.updateStatus(op.tenantId(),"APTOS",op.reference(),"FAILED",signed.txId(),"committed Aptos execution failure",null))==1,"Aptos collection settlement raced");return true;
    }
    private MpcAptosTransactionCodec.Signed selected(Operation op,MpcSignRequest request){require(request.state()==MpcSignRequest.State.COMPLETED,"Aptos MPC result is not completed");var p=payload(request);require(signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"Aptos selected joint key differs");return MpcAptosTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());}
    private static void validate(Owner owner,AptosSigningPayload payload) {
        require(payload!=null && owner.amount().signum()>0 && owner.amount().compareTo(payload.amount())==0 && owner.fee().compareTo(payload.customerFee())==0 && owner.symbol().equals(payload.symbol())
                && owner.sender().equals(payload.unsigned().sender()) && MpcAptosTransactionCodec.canonicalAddress(owner.recipient()).equals(payload.unsigned().recipient())
                && payload.decimals()>=0 && payload.decimals()<=18 && owner.amount().movePointRight(payload.decimals()).longValueExact()==payload.unsigned().amountAtomic(),"Aptos payload differs from exact owned payment");
        require(("APT".equals(owner.symbol()) && payload.decimals()==8 && payload.unsigned().metadata()==null)||(!"APT".equals(owner.symbol()) && payload.unsigned().metadata()!=null),"Aptos asset kind differs");
    }
    private Owner lockOwner(Operation op){if(op.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(op.tenantId(),"APTOS",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}var row=collections.lockForSubmission(op.tenantId(),"APTOS",op.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());}
    private static AptosTransactionRecord record(Owner owner,AptosSigningPayload p,MpcAptosTransactionCodec.Signed signed,String status,Long version,int confirmations,long used,long fee){return AptosTransactionRecord.builder().chain("APTOS").txHash(signed.txId()).sender(owner.sender()).receiver(owner.recipient()).assetSymbol(p.symbol()).coinType(p.unsigned().metadata()==null?AptosRpcClient.aptCoinType():p.unsigned().metadata())
            .amount(owner.amount()).gasUsed(used).gasUnitPrice(p.unsigned().gasUnitPrice()).feeOctas(fee).version(version).sequenceNumber(p.unsigned().sequence()).confirmations(confirmations).status(status).rawPayload(signed.json()).build();}
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

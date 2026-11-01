package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.hypercore.MpcHyperCoreActionCodec;
import com.surprising.wallet.chain.hypercore.HyperCoreExecutionReceipt;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Immutable user-signed action, owned sequence and selected request commit before /exchange. */
@Service
@RequiredArgsConstructor
public class HyperCoreSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="HYPERCORE_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final HyperCoreActionRepository actions;
    private final HyperCoreTokenMetadataRepository metadata;
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
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("HyperCore reference required");}
    }
    public record Prepared(String actionId,String body) { }
    public Optional<MpcSignRequest> intent(Operation operation) {
        return requests.findBusiness(operation.tenantId(),ChainType.HYPERCORE,MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT);
    }
    public HyperCoreSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.HYPERCORE && ENCODING.equals(request.command().payloadEncoding()),"HyperCore encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),HyperCoreSigningPayload.class);
        require(HEX.formatHex(MpcHyperCoreActionCodec.digest(payload.unsigned())).equals(request.command().preimageHex()),"HyperCore fixed digest differs");return payload;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public String recipient(Operation operation) {return lockOwner(operation).recipient();}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation operation,ChainAddressRecord source,String activatedRecipient) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"HyperCore intent requires a transaction");
        var owner=lockOwner(operation);
        require(operation.tenantId().equals(source.getTenantId()) && "HYPERCORE".equals(source.getChain()) && owner.sender().equals(source.getAddress()),"HyperCore source differs from tenant business");
        var previous=intent(operation);
        if(previous.isPresent()) {require(previous.get().command().keyId().equals(source.getMpcKeyId()),"HyperCore key changed");validate(owner,payload(previous.get()));return previous.get();}
        require(owner.hash()==null && "SIGNING".equals(owner.state()),"HyperCore business is not available for signing");
        require(owner.recipient().equals(activatedRecipient),"HyperCore recipient differs from the activated account preflight");
        var profile=profiles.findAccountByChain("HYPERCORE").orElseThrow();
        require("mainnet".equals(profile.getNetwork()) || "testnet".equals(profile.getNetwork()),"unsupported HyperCore signing network");
        var key=signing.lockActiveKey(operation.tenantId(),source.getMpcKeyId(),ChainType.HYPERCORE);
        require(source.getAddress().equals(MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex())))
                && key.userId()==source.getUserId() && key.biz()==source.getBiz() && key.addressIndex()==source.getAddressIndex(),"HyperCore source differs from joint key");
        // Deposits observe spot balances; payouts must spend the same pocket, including USDC.
        var transfer=metadata.findTransferToken(profile.getNetwork(),owner.symbol()).orElseThrow();
        String wireToken=transfer.wireToken();int decimals;
        if(profile.getNativeSymbol().equals(owner.symbol())) {
            require("USDC".equals(owner.symbol()) && transfer.canonical() && transfer.decimals()>=6,"HyperCore quote asset is not canonical spot USDC");decimals=6;
        }
        else {
            var token=tokens.findDefinition("HYPERCORE",profile.getNetwork(),owner.symbol()).orElseThrow();
            require(token.getDecimals()==transfer.decimals() && token.getContractAddress()!=null
                    && token.getContractAddress().equalsIgnoreCase(transfer.tokenId()),"HyperCore token identity or precision differs from configured asset");
            decimals=token.getDecimals();
        }
        require(decimals>=0 && decimals<=36,"HyperCore asset precision is invalid");
        owner.amount().movePointRight(decimals).toBigIntegerExact();
        if(operation.kind()==Kind.COLLECTION)require(addresses.findEnabledByTenantAndAddress(operation.tenantId(),"HYPERCORE",owner.recipient()).orElseThrow().getMpcKeyId()!=null,"HyperCore collector has no owned joint key");
        long nonce=sequences.reserve("HYPERCORE",source.getAddress(),System.currentTimeMillis());
        var unsigned=MpcHyperCoreActionCodec.prepare(source.getAddress(),owner.recipient(),owner.amount(),wireToken,nonce,"mainnet".equals(profile.getNetwork()));
        var payload=new HyperCoreSigningPayload(owner.symbol(),owner.amount(),owner.fee(),unsigned);validate(owner,payload);
        return signing.reserveSignature(new MpcSignCommand(operation.tenantId(),key.id(),key.activeEpoch(),ChainType.HYPERCORE,
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT,MpcSignCommand.Purpose.TRANSACTION,ENCODING,
                HEX.formatHex(json.writeValueAsBytes(payload)),HEX.formatHex(MpcHyperCoreActionCodec.digest(unsigned))),nodes.online());
    }
    public void sign(Operation operation,UUID id) {require(intent(operation).orElseThrow().id().equals(id),"HyperCore request differs");signing.awaitSignature(operation.tenantId(),id,nodes);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Prepared commit(Operation operation,UUID id) {
        var owner=lockOwner(operation);var request=intent(operation).orElseThrow();require(request.id().equals(id),"HyperCore selected request differs");
        var payload=payload(request);validate(owner,payload);var prepared=selected(operation,request);
        var existing=actions.find(prepared.actionId());
        if(existing.isPresent())requireAction(operation,request,prepared,existing.get());
        else {
            require(owner.hash()==null && ("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state())),"HyperCore owner cannot bind request");
            var action=MpcHyperCoreActionCodec.restore(payload.unsigned());
            actions.create(operation.tenantId(),request.id(),prepared.actionId(),action.path("type").asText(),"HYPERCORE",payload.symbol(),owner.sender(),owner.recipient(),owner.amount(),action.path("time").asLong(),prepared.body(),Timestamp.from(Instant.now()));
        }
        if(owner.hash()!=null)require(owner.hash().equals(prepared.actionId()),"HyperCore action ID differs from owned business");
        else require((operation.kind()==Kind.WITHDRAWAL?orders.bindPrepared(operation.tenantId(),"HYPERCORE",operation.reference(),prepared.actionId())
                :collections.bindPrepared(operation.tenantId(),"HYPERCORE",operation.reference(),prepared.actionId()))==1,"HyperCore raw/owner binding raced");
        return prepared;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void accepted(Operation operation,String id,JsonNode response) {
        var owner=lockOwner(operation);var request=intent(operation).orElseThrow();validate(owner,payload(request));var selected=selected(operation,request);
        require(selected.actionId().equals(id) && id.equals(owner.hash()),"HyperCore acceptance has no owned committed body");
        requireAction(operation,request,selected,actions.find(id).orElseThrow());
        require(acceptedResponse(response),"HyperCore response does not prove successful action execution");
        actions.markAccepted(id,response.toString(),Timestamp.from(Instant.now()));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void proven(Operation operation,String id,JsonNode receipt) {
        var owner=lockOwner(operation);var request=intent(operation).orElseThrow();var payload=payload(request);validate(owner,payload);
        var selected=selected(operation,request);require(id.equals(owner.hash()) && id.equals(selected.actionId()),"HyperCore receipt owner differs");
        var action=actions.find(id).orElseThrow();requireAction(operation,request,selected,action);
        HyperCoreExecutionReceipt.verify(payload.unsigned(),receipt);
        if(action.receipt()!=null)require(json.readTree(action.receipt()).equals(receipt),"HyperCore original execution receipt changed");
        else require(actions.recordReceipt(operation.tenantId(),request.id(),id,receipt.toString())==1,"HyperCore receipt publication raced");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation operation,String id) {
        var owner=lockOwner(operation);var request=intent(operation).orElseThrow();
        requireAction(operation,request,selected(operation,request),actions.find(id).orElseThrow());
        require(id.equals(owner.hash()) && actions.accepted(id),"HyperCore recovery has no accepted owned action");
        if(!("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state())))return;
        require((operation.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(operation.tenantId(),"HYPERCORE",operation.reference(),id)
                :collections.recoverPreparedSent(operation.tenantId(),"HYPERCORE",operation.reference(),id))==1,"HyperCore SENT recovery raced");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation operation,String reason) {
        var owner=lockOwner(operation);
        if(owner.hash()!=null) {
            var request=intent(operation).orElseThrow();requireAction(operation,request,selected(operation,request),actions.find(owner.hash()).orElseThrow());
            actions.markUnknown(owner.hash(),reason,Timestamp.from(Instant.now()));
        }
        if(!"SIGNING".equals(owner.state()))return;
        if(operation.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(operation.tenantId(),"HYPERCORE",operation.reference(),owner.sender(),reason);
        else collections.markSigningUnknown(operation.tenantId(),"HYPERCORE",operation.reference(),reason);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation operation) {
        var owner=lockOwner(operation);if(owner.hash()!=null || intent(operation).isPresent())return 0;
        return operation.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(operation.tenantId(),"HYPERCORE",operation.reference())
                :collections.recoverUnsignedDurable(operation.tenantId(),"HYPERCORE",operation.reference());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation operation,String id) {
        var owner=lockOwner(operation);var request=intent(operation).orElseThrow();validate(owner,payload(request));var selected=selected(operation,request);
        require(id.equals(owner.hash()) && id.equals(selected.actionId()),"HyperCore settlement owner differs");
        var action=actions.find(id).orElseThrow();requireAction(operation,request,selected,action);
        if(!"ACCEPTED".equals(action.status()) || action.receipt()==null)return false;
        HyperCoreExecutionReceipt.verify(payload(request).unsigned(),json.readTree(action.receipt()));
        if("CONFIRMED".equals(owner.state()))return false;
        require("SENT".equals(owner.state())||"CONFIRMING".equals(owner.state()),"HyperCore business is not confirmable");
        return operation.kind()==Kind.WITHDRAWAL?settlements.confirm(operation.tenantId(),"HYPERCORE",operation.reference(),id)
                :collections.markConfirmed(operation.tenantId(),"HYPERCORE",operation.reference(),id)==1;
    }
    private static void requireAction(Operation operation,MpcSignRequest request,Prepared prepared,HyperCoreActionRepository.Action action) {
        require(operation.tenantId().equals(action.tenantId()) && request.id().equals(action.requestId())
                && prepared.actionId().equals(action.id()) && prepared.body().equals(action.raw()),"HyperCore action tenant, request or selected body differs");
    }
    private Prepared selected(Operation operation,MpcSignRequest request) {
        require(request.state()==MpcSignRequest.State.COMPLETED,"HyperCore signature is incomplete");var payload=payload(request);
        var body=MpcHyperCoreActionCodec.assemble(payload.unsigned(),HEX.parseHex(signing.requireKey(operation.tenantId(),request.command().keyId()).publicKeyHex()),
                signing.signature(operation.tenantId(),request.id()).orElseThrow());
        var action=body.path("action");
        return new Prepared("HC-"+action.path("type").asText()+"-"+payload.unsigned().owner()+"-"+action.path("time").asLong(),body.toString());
    }
    public static boolean acceptedResponse(JsonNode response) {
        return response!=null && "ok".equals(response.path("status").asText())
                && "default".equals(response.path("response").path("type").asText())
                && !response.hasNonNull("error") && !response.path("response").hasNonNull("error");
    }
    private Owner lockOwner(Operation operation) {
        if(operation.kind()==Kind.WITHDRAWAL) {
            var row=orders.lockForSettlement(operation.tenantId(),"HYPERCORE",operation.reference()).orElseThrow();
            return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());
        }
        var row=collections.lockForSubmission(operation.tenantId(),"HYPERCORE",operation.reference()).orElseThrow();
        return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());
    }
    private static void validate(Owner owner,HyperCoreSigningPayload payload) {
        var action=MpcHyperCoreActionCodec.restore(payload.unsigned());
        require(owner.sender().equals(payload.unsigned().owner()) && owner.recipient().equals(action.path("destination").asText())
                && owner.amount().compareTo(new BigDecimal(action.path("amount").asText()))==0 && owner.amount().compareTo(payload.amount())==0
                && owner.symbol().equals(payload.symbol()) && owner.fee().compareTo(payload.customerFee())==0,"HyperCore payload differs from owned payment");
    }
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

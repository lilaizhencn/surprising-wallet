package com.surprising.wallet.service;

import com.google.common.primitives.UnsignedInteger;
import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.XrpTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.xrp.MpcXrpTransactionCodec;
import com.surprising.wallet.chain.xrp.XrpExecutionReceipt;
import com.surprising.wallet.chain.xrp.XrpIssuedCurrency;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.model.mpc.XrpSigningPayload;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reserved sequence + immutable intent -> remote MPC -> durable signed bytes -> network. */
@Service
@RequiredArgsConstructor
public class XrpSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="XRPL_TX_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final XrpTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final TokenConfigRepository tokens;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    private final CustodyGasAccountingService accounting;

    public enum Kind { WITHDRAWAL, COLLECTION, FUNDING }
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {
            Objects.requireNonNull(tenantId,"XRPL tenant is required");
            Objects.requireNonNull(kind,"XRPL business kind is required");
            if(reference==null || reference.isBlank())throw new IllegalArgumentException("XRPL reference is required");
        }
    }
    public Optional<MpcSignRequest> intent(Operation operation) {
        return requests.findBusiness(operation.tenantId(),ChainType.XRP,MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT);
    }
    public XrpSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.XRP && ENCODING.equals(request.command().payloadEncoding()),"XRPL intent encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),XrpSigningPayload.class);
        require(HEX.formatHex(MpcXrpTransactionCodec.digest(payload.unsigned())).equals(request.command().preimageHex()),"XRPL immutable digest differs");
        return payload;
    }
    /** Caller obtains fee, observed account sequence and expiry before this database transaction. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation operation,ChainAddressRecord source,String symbol,BigDecimal amount,
                                  Transaction draft) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"XRPL reservation requires a transaction");
        Owner owner=lockOwner(operation);
        var key=signing.lockActiveKey(operation.tenantId(),source.getMpcKeyId(),ChainType.XRP);
        require(operation.tenantId().equals(source.getTenantId()) && "XRP".equals(source.getChain())
                        && source.getAddress().equals(MpcPublicAddressCodec.encode(profiles.findAccountByChain("XRP").orElseThrow(),HEX.parseHex(key.publicKeyHex())))
                        && key.userId()==source.getUserId() && key.biz()==source.getBiz() && key.addressIndex()==source.getAddressIndex(),
                "XRPL source differs from owned joint key");
        var previous=intent(operation);
        if(previous.isPresent()) {
            require(previous.get().command().keyId().equals(key.id()),"XRPL request key differs");
            validate(operation,owner,payload(previous.get()));
            return previous.get();
        }
        require(owner==null || (owner.hash()==null && "SIGNING".equals(owner.state())),"XRPL business is not available for signing");
        require(draft.account().value().equals(source.getAddress()),"XRPL draft owner differs");
        long sequence=sequences.reserve("XRP",source.getAddress(),draft.sequence().longValue());
        var pub=PublicKey.fromBase16EncodedPublicKey(key.publicKeyHex());
        Transaction fixed=switch(draft) {
            case Payment payment -> Payment.builder().from(payment).sequence(UnsignedInteger.valueOf(sequence)).signingPublicKey(pub).build();
            case TrustSet trust -> TrustSet.builder().from(trust).sequence(UnsignedInteger.valueOf(sequence)).signingPublicKey(pub).build();
            default -> throw new IllegalArgumentException("unsupported XRPL operation");
        };
        var payload=new XrpSigningPayload(symbol,amount,owner==null?BigDecimal.ZERO:owner.fee(),MpcXrpTransactionCodec.prepare(fixed));
        validate(operation,owner,payload);
        validateAsset(operation,payload);
        if(operation.kind()==Kind.COLLECTION) {
            var target=addresses.findEnabledByTenantAndAddress(operation.tenantId(),"XRP",((Payment)fixed).destination().value()).orElseThrow();
            require(target.getMpcKeyId()!=null,"XRPL collector is not a tenant joint address");
        }
        var request=signing.reserveSignature(new MpcSignCommand(operation.tenantId(),key.id(),key.activeEpoch(),ChainType.XRP,
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT,MpcSignCommand.Purpose.TRANSACTION,
                ENCODING,HEX.formatHex(json.writeValueAsBytes(payload)),HEX.formatHex(MpcXrpTransactionCodec.digest(payload.unsigned()))),nodes.online());
        if(operation.kind()==Kind.FUNDING) {
            BigDecimal cost=BigDecimal.valueOf(fixed.fee().value().longValue()).movePointLeft(6);
            if(fixed instanceof Payment)cost=cost.add(payload.amount());
            boolean reserved=accounting.reservePreparation(operation.tenantId(),request.id(),source.getAddress(),cost);
            require(!(fixed instanceof Payment)||reserved,"XRPL activation requires a funded tenant Gas ledger");
        }
        return request;
    }
    public void sign(Operation operation,UUID id) {
        require(intent(operation).orElseThrow().id().equals(id),"XRPL signing request owner differs");
        signing.awaitSignature(operation.tenantId(),id,nodes);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcXrpTransactionCodec.Signed commit(Operation operation,UUID id) {
        Owner owner=lockOwner(operation);
        var request=intent(operation).orElseThrow();
        require(request.id().equals(id) && request.state()==MpcSignRequest.State.COMPLETED,"XRPL signature is not completed owned request");
        var payload=payload(request);validate(operation,owner,payload);
        var signed=MpcXrpTransactionCodec.assemble(payload.unsigned(),HEX.parseHex(signing.requireKey(operation.tenantId(),request.command().keyId()).publicKeyHex()),
                signing.signature(operation.tenantId(),id).orElseThrow());
        var existing=transactions.findSigned(signed.txId());
        if(existing.isPresent())require(existing.get().getRawPayload().equals(signed.transactionHex()),"XRPL selected raw changed");
        else {
            require(owner==null || (owner.hash()==null && ("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state()))),"XRPL business cannot bind raw");
            transactions.upsert(record(payload,signed,"PREPARED",null,0));
        }
        if(owner!=null) {
            if(owner.hash()!=null)require(owner.hash().equals(signed.txId()),"XRPL business hash differs from selected signature");
            else require((operation.kind()==Kind.WITHDRAWAL
                    ?orders.bindPrepared(operation.tenantId(),"XRP",operation.reference(),signed.txId())
                    :collections.bindPrepared(operation.tenantId(),"XRP",operation.reference(),signed.txId()))==1,"XRPL business raw/hash commit raced");
        }
        return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation operation,String hash) {
        Owner owner=lockOwner(operation);
        var request=intent(operation).orElseThrow();
        var signed=selected(operation,request);
        require(signed.txId().equals(hash) && (owner==null || hash.equals(owner.hash())),"XRPL observation owner differs");
        transactions.markSubmitted(hash);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation operation,String hash) {
        Owner owner=lockOwner(operation);
        require(owner!=null && hash.equals(owner.hash()) && intent(operation).isPresent(),"XRPL recovery owner differs");
        if(owner!=null && ("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state()))) {
            require((operation.kind()==Kind.WITHDRAWAL
                    ?orders.recoverPreparedSent(operation.tenantId(),"XRP",operation.reference(),hash)
                    :collections.recoverPreparedSent(operation.tenantId(),"XRP",operation.reference(),hash))==1,"XRPL SENT observation raced");
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation operation) {
        Owner owner=lockOwner(operation);
        if(owner==null || owner.hash()!=null || intent(operation).isPresent())return 0;
        return operation.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(operation.tenantId(),"XRP",operation.reference())
                :collections.recoverUnsignedDurable(operation.tenantId(),"XRP",operation.reference());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation operation,JsonNode receipt,int requiredConfirmations,long latestLedger) {
        Owner owner=lockOwner(operation);
        var request=intent(operation).orElseThrow();var payload=payload(request);
        validate(operation,owner,payload);
        var signed=selected(operation,request);
        require(owner==null || signed.txId().equals(owner.hash()),"XRPL receipt owner differs");
        if(!receipt.path("validated").asBoolean(false))return false;
        var outcome=XrpExecutionReceipt.verify(receipt,payload.unsigned(),signed);
        long confirmations=latestLedger-outcome.ledger()+1;
        if(confirmations<Math.max(1,requiredConfirmations))return false;
        if(owner!=null && ("CONFIRMED".equals(owner.state())||"FAILED".equals(owner.state())))return false;
        require(transactions.findSigned(signed.txId()).isPresent(),"XRPL receipt has no selected raw");
        transactions.upsert(record(payload,signed,outcome.paid()?"CONFIRMED":"FAILED",outcome.ledger(),(int)Math.min(Integer.MAX_VALUE,confirmations)));
        if(owner==null) {
            BigDecimal cost=BigDecimal.valueOf(outcome.fee()).movePointLeft(6);
            if(outcome.paid() && MpcXrpTransactionCodec.restore(payload.unsigned()) instanceof Payment)cost=cost.add(payload.amount());
            accounting.settlePreparation(operation.tenantId(),request.id(),cost,signed.txId());
            return outcome.paid();
        }
        require("SENT".equals(owner.state())||"CONFIRMING".equals(owner.state()),"XRPL business is not confirmable");
        if(operation.kind()==Kind.WITHDRAWAL)return outcome.paid()
                ?settlements.confirm(operation.tenantId(),"XRP",operation.reference(),signed.txId())
                :settlements.fail(operation.tenantId(),"XRP",operation.reference(),signed.txId(),"validated XRPL execution failure");
        require(collections.updateAmounts(operation.tenantId(),"XRP",operation.reference(),owner.amount(),BigDecimal.valueOf(outcome.fee()).movePointLeft(6))==1,"XRPL collection fee update raced");
        require((outcome.paid()?collections.markConfirmed(operation.tenantId(),"XRP",operation.reference(),signed.txId())
                :collections.updateStatus(operation.tenantId(),"XRP",operation.reference(),"FAILED",signed.txId(),"validated XRPL execution failure",null))==1,"XRPL collection settlement raced");
        return true;
    }
    private MpcXrpTransactionCodec.Signed selected(Operation operation,MpcSignRequest request) {
        require(request.state()==MpcSignRequest.State.COMPLETED,"XRPL signature is incomplete");
        return MpcXrpTransactionCodec.assemble(payload(request).unsigned(),HEX.parseHex(signing.requireKey(operation.tenantId(),request.command().keyId()).publicKeyHex()),
                signing.signature(operation.tenantId(),request.id()).orElseThrow());
    }
    private Owner lockOwner(Operation operation) {
        if(operation.kind()==Kind.FUNDING)return null; // The tenant-owned immutable request is the system operation receipt.
        if(operation.kind()==Kind.WITHDRAWAL) {
            var row=orders.lockForSettlement(operation.tenantId(),"XRP",operation.reference()).orElseThrow();
            return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());
        }
        var row=collections.lockForSubmission(operation.tenantId(),"XRP",operation.reference()).orElseThrow();
        return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());
    }
    private void validate(Operation operation,Owner owner,XrpSigningPayload payload) {
        var tx=MpcXrpTransactionCodec.restore(payload.unsigned());
        require(payload.amount()!=null && payload.customerFee()!=null,"XRPL business amounts are missing");
        if(owner!=null) {
            require(tx instanceof Payment,"XRPL financial business requires Payment");
            var payment=(Payment)tx;
            require(owner.sender().equals(tx.account().value()) && owner.recipient().equals(payment.destination().value())
                            && owner.symbol().equals(payload.symbol()) && owner.amount().compareTo(payload.amount())==0
                            && owner.fee().compareTo(payload.customerFee())==0,"XRPL payload differs from owned payment");
        } else {
            String target=tx instanceof Payment payment?payment.destination().value():tx.account().value();
            var address=addresses.findEnabledByTenantAndAddress(operation.tenantId(),"XRP",target).orElseThrow();
            require(address.getMpcKeyId()!=null && operation.reference().equals(preparationReference(address,payload.symbol(),tx instanceof TrustSet)),
                    "XRPL preparation reference differs from tenant target");
            require(payload.customerFee().signum()==0,"XRPL preparation has a customer fee");
            if(tx instanceof TrustSet trust)require(new BigDecimal(trust.limitAmount().value()).compareTo(new BigDecimal("1000000000"))==0
                    && payload.amount().signum()==0,"XRPL preparation trust limit differs");
            else require(((Payment)tx).amount() instanceof XrpCurrencyAmount,"XRPL preparation payment must use native XRP");
        }
        if(tx instanceof Payment payment) {
            require(payload.amount().signum()>0,"XRPL payment amount must be positive");
            BigDecimal actual=payment.amount() instanceof XrpCurrencyAmount xrp?new BigDecimal(xrp.value().bigIntegerValue()).movePointLeft(6)
                    :new BigDecimal(((IssuedCurrencyAmount)payment.amount()).value());
            require(actual.compareTo(payload.amount())==0,"XRPL binary amount differs from business amount");
        }
    }
    private void validateAsset(Operation operation,XrpSigningPayload payload) {
        var tx=MpcXrpTransactionCodec.restore(payload.unsigned());
        var amount=tx instanceof Payment payment?payment.amount():((TrustSet)tx).limitAmount();
        if(amount instanceof XrpCurrencyAmount) {
            if(operation.kind()==Kind.FUNDING) tokens.findDefinition("XRP",profiles.findAccountByChain("XRP").orElseThrow().getNetwork(),payload.symbol()).orElseThrow();
            else require("XRP".equals(payload.symbol()),"XRPL native asset differs");
        }
        else {
            var profile=profiles.findAccountByChain("XRP").orElseThrow();
            var token=tokens.findDefinition("XRP",profile.getNetwork(),payload.symbol()).orElseThrow();
            var issued=(IssuedCurrencyAmount)amount;
            require(XrpIssuedCurrency.fromToken(token).matches(issued.issuer().value(),issued.currency()),"XRPL issuer/currency differs from enabled asset");
            if(tx instanceof Payment)payload.amount().movePointRight(token.getDecimals()).toBigIntegerExact();
        }
    }
    public static String preparationReference(ChainAddressRecord target,String symbol,boolean trust) {
        return "prepare:"+Objects.requireNonNull(target.getMpcKeyId())+":"+symbol+":"+(trust?"trust":"fund");
    }
    private static XrpTransactionRecord record(XrpSigningPayload payload,MpcXrpTransactionCodec.Signed signed,String state,Long ledger,int confirmations) {
        var tx=MpcXrpTransactionCodec.restore(payload.unsigned());
        var issued=tx instanceof TrustSet trust?trust.limitAmount():((Payment)tx).amount() instanceof IssuedCurrencyAmount iou?iou:null;
        return XrpTransactionRecord.builder().chain("XRP").txHash(signed.txId()).fromAddress(tx.account().value())
                .toAddress(tx instanceof Payment payment?payment.destination().value():((TrustSet)tx).limitAmount().issuer().value())
                .assetSymbol(tx instanceof Payment payment && payment.amount() instanceof XrpCurrencyAmount && !"XRP".equals(payload.symbol())?"XRP_ACTIVATION":payload.symbol()).issuerAddress(issued==null?null:issued.issuer().value()).currencyCode(issued==null?null:issued.currency())
                .amount(payload.amount()).feeDrops(tx.fee().value().longValue()).sequenceNumber(tx.sequence().longValue())
                .ledgerIndex(ledger).confirmations(confirmations).status(state).rawPayload(signed.transactionHex()).build();
    }
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}

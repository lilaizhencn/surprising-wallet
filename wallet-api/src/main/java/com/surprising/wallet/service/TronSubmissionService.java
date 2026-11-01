package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.TronTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.chain.tron.TronExecutionReceipt;
import org.tron.trident.proto.Response;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.common.signing.TronSigningPayload;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.TronTransactionRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
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
import tools.jackson.databind.ObjectMapper;

/** Fixed TAPOS/expiry intent commit -> remote MPC -> exact raw/business hash commit -> RPC. */
@Service
@RequiredArgsConstructor
public class TronSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="transaction",ENCODING="TRON_TX_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final TronTransactionRepository transactions;
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
        public Operation {
            Objects.requireNonNull(tenantId,"TRON tenant is required");
            Objects.requireNonNull(kind,"TRON business kind is required");
            if(reference==null || reference.isBlank()) throw new IllegalArgumentException("TRON business reference is required");
        }
    }

    public Optional<MpcSignRequest> intent(Operation operation) {
        return requests.findBusiness(operation.tenantId(),ChainType.TRON,
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT);
    }

    public TronSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.TRON && ENCODING.equals(request.command().payloadEncoding()),"TRON MPC encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),TronSigningPayload.class);
        require(HEX.formatHex(MpcTronTransactionCodec.digest(payload.unsigned())).equals(request.command().preimageHex()),"TRON digest differs from fixed payload");
        return payload;
    }

    /** The unsigned transaction was built without a transaction; only database work occurs here. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation operation,ChainAddressRecord source,TronSigningPayload draft) {
        requireTransaction();
        var owner=lockOwner(operation);
        require(operation.tenantId().equals(source.getTenantId()) && "TRON".equals(source.getChain())
                        && owner.sender().equals(source.getAddress()),"TRON source differs from owned business");
        var existing=intent(operation);
        if(existing.isPresent()) {
            require(existing.get().command().keyId().equals(source.getMpcKeyId()),"TRON intent uses another source key");
            validate(operation,owner,payload(existing.get()));
            return existing.get();
        }
        require(owner.hash()==null && "SIGNING".equals(owner.state()),"TRON business is not available for signing");
        var profile=profiles.findAccountByChain("TRON").orElseThrow();
        var key=signing.lockActiveKey(operation.tenantId(),source.getMpcKeyId(),ChainType.TRON);
        require(source.getAddress().equals(MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex())))
                        && key.userId()==source.getUserId() && key.biz()==source.getBiz() && key.addressIndex()==source.getAddressIndex(),
                "TRON source differs from its tenant joint key");
        validate(operation,owner,draft);
        if(operation.kind()==Kind.COLLECTION) {
            var target=addresses.findEnabledByTenantAndAddress(operation.tenantId(),"TRON",draft.unsigned().recipient()).orElseThrow();
            require(target.getMpcKeyId()!=null,"TRON collection destination has no tenant MPC key");
        }
        if("TRX".equals(owner.symbol())) {
            require(draft.decimals()==6 && draft.unsigned().contract()==null,"TRX precision or contract differs");
        } else {
            var token=tokens.findDefinition("TRON",profile.getNetwork(),owner.symbol()).orElseThrow();
            require(draft.decimals()==token.getDecimals() && token.getContractAddress().equals(draft.unsigned().contract()),
                    "TRC20 contract or precision differs from enabled asset configuration");
        }
        var command=new MpcSignCommand(operation.tenantId(),key.id(),key.activeEpoch(),ChainType.TRON,
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),SLOT,
                MpcSignCommand.Purpose.TRANSACTION,ENCODING,HEX.formatHex(json.writeValueAsBytes(draft)),
                HEX.formatHex(MpcTronTransactionCodec.digest(draft.unsigned())));
        return signing.reserveSignature(command,nodes.online());
    }

    public void sign(Operation operation,UUID requestId) {
        var request=intent(operation).orElseThrow();
        require(request.id().equals(requestId),"TRON signing request differs from its owner");
        signing.awaitSignature(operation.tenantId(),requestId,nodes);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcTronTransactionCodec.Signed commit(Operation operation,UUID requestId) {
        requireTransaction();
        var owner=lockOwner(operation);
        var request=intent(operation).orElseThrow();
        require(request.id().equals(requestId) && request.state()==MpcSignRequest.State.COMPLETED,"TRON MPC result is not the completed owned request");
        var payload=payload(request);
        validate(operation,owner,payload);
        var key=signing.requireKey(operation.tenantId(),request.command().keyId());
        var signed=MpcTronTransactionCodec.assemble(payload.unsigned(),HEX.parseHex(key.publicKeyHex()),
                signing.signature(operation.tenantId(),requestId).orElseThrow());
        if(owner.hash()!=null) {
            require(owner.hash().equals(signed.txId()),"TRON business hash differs from selected signature");
            require(signed.transactionHex().equals(transactions.findSigned(signed.txId()).orElseThrow().getRawPayload()),"TRON durable raw differs from selected signature");
            return signed;
        }
        require("SIGNING".equals(owner.state()) || "BROADCAST_UNKNOWN".equals(owner.state()),"TRON owner cannot bind raw");
        require(transactions.upsert(TronTransactionRecord.builder().chain("TRON").txHash(signed.txId())
                .fromAddress(owner.sender()).toAddress(owner.recipient()).assetSymbol(payload.symbol())
                .contractAddress(payload.unsigned().contract()).amount(payload.amount()).fee(BigDecimal.ZERO)
                .confirmations(0).status("PREPARED").rawPayload(signed.transactionHex()).build())==1,"TRON raw could not be committed");
        int changed=operation.kind()==Kind.WITHDRAWAL
                ? orders.bindPrepared(operation.tenantId(),"TRON",operation.reference(),signed.txId())
                : collections.bindPrepared(operation.tenantId(),"TRON",operation.reference(),signed.txId());
        require(changed==1,"TRON business changed before raw/hash commit");
        return signed;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation operation,String hash) {
        var owner=lockOwner(operation);
        require(hash!=null && hash.equals(owner.hash()),"TRON observed hash differs from owned intent");
        transactions.markSubmitted(hash);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation operation,String hash) {
        var owner=lockOwner(operation);
        require(hash.equals(owner.hash()) && intent(operation).isPresent(),"TRON recovery owner differs");
        if(!("SIGNING".equals(owner.state()) || "BROADCAST_UNKNOWN".equals(owner.state()))) return;
        int changed=operation.kind()==Kind.WITHDRAWAL
                ? orders.recoverPreparedSent(operation.tenantId(),"TRON",operation.reference(),hash)
                : collections.recoverPreparedSent(operation.tenantId(),"TRON",operation.reference(),hash);
        require(changed==1,"TRON business recovery raced");
        transactions.markSubmitted(hash);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation operation,String reason) {
        var owner=lockOwner(operation);
        if(!"SIGNING".equals(owner.state())) return;
        int changed=operation.kind()==Kind.WITHDRAWAL
                ? orders.markBroadcastUnknown(operation.tenantId(),"TRON",operation.reference(),owner.sender(),reason)
                : collections.markSigningUnknown(operation.tenantId(),"TRON",operation.reference(),reason);
        require(changed==1,"TRON unknown observation raced");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation operation) {
        var owner=lockOwner(operation);
        if(owner.hash()!=null || intent(operation).isPresent()) return 0;
        return operation.kind()==Kind.WITHDRAWAL
                ? orders.recoverUnsignedDurable(operation.tenantId(),"TRON",operation.reference())
                : collections.recoverUnsignedDurable(operation.tenantId(),"TRON",operation.reference());
    }

    /** Network receipt collection is outside this transaction; selected payment, ledger and chain fact commit together. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation operation,Response.TransactionInfo receipt) {
        var owner=lockOwner(operation);
        var request=intent(operation).orElseThrow();
        require(request.state()==MpcSignRequest.State.COMPLETED,"TRON receipt has no completed owned MPC request");
        var payload=payload(request);validate(operation,owner,payload);
        String hash=HEX.formatHex(MpcTronTransactionCodec.digest(payload.unsigned()));
        require(hash.equals(owner.hash()),"TRON receipt differs from owned business hash");
        var outcome=TronExecutionReceipt.verify(receipt,payload.unsigned());
        if("CONFIRMED".equals(owner.state()) || "FAILED".equals(owner.state())) return false;
        require("SENT".equals(owner.state()) || "CONFIRMING".equals(owner.state()),"TRON business is not confirmable");
        require(transactions.findSigned(hash).isPresent(),"TRON receipt has no durable raw");
        require(transactions.upsert(TronTransactionRecord.builder().chain("TRON").txHash(hash).fromAddress(owner.sender())
                .toAddress(owner.recipient()).assetSymbol(payload.symbol()).contractAddress(payload.unsigned().contract()).amount(payload.amount())
                .fee(BigDecimal.valueOf(receipt.getFee()).movePointLeft(6)).blockHeight(receipt.getBlockNumber()).confirmations(1)
                .status(outcome==TronExecutionReceipt.Outcome.PAID ? "CONFIRMED":"FAILED").build())==1,"TRON receipt fact could not be persisted");
        if(operation.kind()==Kind.WITHDRAWAL) {
            return outcome==TronExecutionReceipt.Outcome.PAID
                    ? settlements.confirm(operation.tenantId(),"TRON",operation.reference(),hash)
                    : settlements.fail(operation.tenantId(),"TRON",operation.reference(),hash,"confirmed TRON execution failure");
        }
        require(collections.updateAmounts(operation.tenantId(),"TRON",operation.reference(),owner.amount(),
                BigDecimal.valueOf(receipt.getFee()).movePointLeft(6))==1,"TRON actual collection fee could not be recorded");
        int changed=outcome==TronExecutionReceipt.Outcome.PAID
                ? collections.markConfirmed(operation.tenantId(),"TRON",operation.reference(),hash)
                : collections.updateStatus(operation.tenantId(),"TRON",operation.reference(),"FAILED",hash,"confirmed TRON execution failure",null);
        require(changed==1,"TRON collection settlement raced");
        return true;
    }

    private Owner lockOwner(Operation operation) {
        if(operation.kind()==Kind.WITHDRAWAL) {
            var owner=orders.lockForSettlement(operation.tenantId(),"TRON",operation.reference()).orElseThrow();
            return new Owner(owner.getStatus(),owner.getFromAddress(),owner.getTxHash(),owner.getToAddress(),owner.getAssetSymbol(),owner.getAmount(),owner.getFee());
        }
        var owner=collections.lockForSubmission(operation.tenantId(),"TRON",operation.reference()).orElseThrow();
        return new Owner(owner.getStatus(),owner.getFromAddress(),owner.getTxHash(),owner.getToAddress(),owner.getAssetSymbol(),owner.getAmount(),BigDecimal.ZERO);
    }
    /** Separates distinct payments even if SDK time, TAPOS, destination and amount coincide. */
    public static byte[] memo(Operation operation) {
        return MpcTronTransactionCodec.businessMemo(operation.tenantId(),operation.kind().name(),operation.reference());
    }
    private static void validate(Operation operation,Owner owner,TronSigningPayload payload) {
        require(payload!=null && payload.decimals()>=0 && payload.decimals()<=36 && owner.amount().compareTo(payload.amount())==0
                        && payload.customerFee()!=null && owner.customerFee().compareTo(payload.customerFee())==0
                        && owner.symbol().equals(payload.symbol()) && owner.sender().equals(payload.unsigned().owner())
                        && owner.recipient().equals(payload.unsigned().recipient())
                        && owner.amount().movePointRight(payload.decimals()).toBigIntegerExact().equals(payload.unsigned().amountAtomic()),
                "TRON payload differs from its owned payment");
        require(java.util.Arrays.equals(memo(operation),MpcTronTransactionCodec.restore(payload.unsigned()).getRawData().getData().toByteArray()),
                "TRON transaction memo differs from its owned business identity");
    }
    private record Owner(String state,String sender,String hash,String recipient,String symbol,BigDecimal amount,BigDecimal customerFee) { }
    private static void requireTransaction() { require(TransactionSynchronizationManager.isActualTransactionActive(),"TRON metadata requires a transaction"); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}

package com.surprising.wallet.service;

import com.surprising.wallet.common.signing.NearRegistrationPayload;

import com.surprising.wallet.common.signing.NearOperation;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.model.NearTransactionRecord;
import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;

import java.math.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Parent-owned, tenant Gas-funded NEP-145 admission and settlement. Chain RPC remains in the workflow. */
@Service
@RequiredArgsConstructor
public class NearRegistrationService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SLOT="storage-registration",ENCODING="NEAR_STORAGE_REGISTRATION_JSON";
    private final NearSubmissionService payments;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainAddressRepository addresses;
    private final CustodyAddressRepository custodyAddresses;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyGasUsageRepository usages;
    private final NearTransactionRepository transactions;
    private final AccountSequenceRepository sequences;
    private final TokenConfigRepository tokens;
    private final CustodyGasAccountingService accounting;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final LedgerBalanceRepository balances;
    private final ObjectMapper json;

    public Optional<MpcSignRequest> intent(NearOperation op){return payments.registration(op);}
    public NearRegistrationPayload payload(NearOperation op){return payload(op,intent(op).orElseThrow(),json);}
    static NearRegistrationPayload payload(NearOperation op,MpcSignRequest request,ObjectMapper json) {
        var c=request.command();
        require(c.tenantId().equals(op.tenantId()) && c.chain()==ChainType.NEAR && c.businessType()==MpcSignCommand.BusinessType.FUNDING
                && c.businessReference().equals(NearSubmissionService.registrationReference(op)) && c.signingSlot().equals(SLOT)
                && c.purpose()==MpcSignCommand.Purpose.TRANSACTION && c.payloadEncoding().equals(ENCODING),"NEP-145 request ownership/encoding differs");
        var p=json.readValue(HEX.parseHex(c.payloadHex()),NearRegistrationPayload.class);
        require(op.equals(p.parent()) && HEX.formatHex(MpcNearTransactionCodec.signingBytes(p.unsigned())).equals(c.preimageHex())
                && p.payment().recipient().equals(p.unsigned().beneficiary()) && !"NEAR".equals(p.payment().symbol()),"NEP-145 original parent or preimage differs");return p;
    }
    public ChainAddressRecord source(NearOperation op) {
        var p=payload(op);var source=addresses.findOwned(op.tenantId(),p.parentAddressId()).orElseThrow();
        require(p.parentSender().equals(source.getAddress()) && source.getMpcKeyId()!=null,"NEP-145 original parent source differs");return source;
    }
    public ChainAddressRecord gasSender(UUID tenant,String network) {
        var gas=gasAccounts.findByChain(tenant,"NEAR").orElseThrow(()->new IllegalStateException("NEAR tenant Gas account is missing"));
        require("ACTIVE".equals(gas.status()) && network.equals(gas.network()),"NEAR Gas account network/status differs");
        var custody=custodyAddresses.require(tenant,gas.custodyAddressId());var sender=addresses.findOwned(tenant,custody.chainAddressId()).orElseThrow();
        require("NEAR".equals(sender.getChain()) && sender.getMpcKeyId()!=null && Boolean.TRUE.equals(sender.getEnabled()),"NEAR Gas sender is not a published owned joint key");return sender;
    }
    public String publicKey(ChainAddressRecord sender){return signing.requireKey(sender.getTenantId(),sender.getMpcKeyId()).publicKeyHex();}

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(NearOperation op,ChainAddressRecord parent,NearRegistrationPayload draft) {
        var previous=intent(op);var p=previous.map(r->payload(op,r,json)).orElseGet(()->Objects.requireNonNull(draft));
        payments.lockRegistrationOwner(op,parent.getAddress(),p.payment());
        require(op.equals(p.parent()) && parent.getId()==p.parentAddressId() && parent.getAddress().equals(p.parentSender())
                && op.tenantId().equals(parent.getTenantId()) && "NEAR".equals(parent.getChain()),"NEP-145 parent source differs");
        if(previous.isPresent())return previous.get();
        require(Boolean.TRUE.equals(addresses.findOwned(op.tenantId(),parent.getId()).orElseThrow().getEnabled()),"NEP-145 parent address is disabled");
        var u=p.unsigned();var sender=gasSender(op.tenantId(),u.network());
        require(sender.getAddress().equals(u.sender()),"NEP-145 payer is not this tenant's Gas account");
        var token=tokens.findEnabled("NEAR",u.network(),p.payment().symbol()).orElseThrow();
        require(token.contractAddress().equals(u.contract()) && token.decimals()==p.tokenDecimals()
                && "NEAR_STORAGE_DEPOSIT".equals(token.gasStrategy()),"NEP-145 contract/asset policy differs");
        require(p.payment().recipient().equals(u.beneficiary()) && p.maximumFeeYocto().signum()>0
                && p.maximumFeeYocto().compareTo(BigInteger.TEN.pow(23))<=0,"NEP-145 beneficiary or Gas budget differs");
        if(op.kind()==NearOperation.Kind.COLLECTION)require(addresses.findEnabledByTenantAndAddress(op.tenantId(),"NEAR",u.beneficiary()).orElseThrow().getMpcKeyId()!=null,"NEP-145 collector has no owned joint key");
        var key=signing.lockActiveKey(op.tenantId(),sender.getMpcKeyId(),ChainType.NEAR);
        require(key.publicKeyHex().equals(u.publicKeyHex()) && key.userId()==sender.getUserId() && key.biz()==sender.getBiz()
                && key.addressIndex()==sender.getAddressIndex(),"NEP-145 Gas joint key differs");
        require(!transactions.hasPendingSender(u.sender()),"NEAR Gas sender has another unresolved transaction");
        long nonce=sequences.reserve("NEAR",u.sender(),u.nonce(),MpcSignCommand.reservationOwner(op.tenantId(),MpcSignCommand.BusinessType.FUNDING,NearSubmissionService.registrationReference(op)));
        var fixed=new NearRegistrationPayload(op,p.payment(),p.parentAddressId(),p.parentSender(),p.tokenDecimals(),p.maximumFeeYocto(),
                new MpcNearTransactionCodec.Registration(u.network(),u.genesisHash(),u.sender(),nonce,u.beneficiary(),u.contract(),u.depositAtomic(),u.gas(),u.blockHeight(),u.validUntilHeight(),u.blockHash(),u.publicKeyHex()));
        var request=signing.reserveSignature(new MpcSignCommand(op.tenantId(),key.id(),key.activeEpoch(),ChainType.NEAR,MpcSignCommand.BusinessType.FUNDING,
                NearSubmissionService.registrationReference(op),SLOT,MpcSignCommand.Purpose.TRANSACTION,ENCODING,
                HEX.formatHex(json.writeValueAsBytes(fixed)),HEX.formatHex(MpcNearTransactionCodec.signingBytes(fixed.unsigned()))),nodes.online());
        require(transactions.insertPending(record(fixed,MpcNearTransactionCodec.transactionHash(fixed.unsigned()),"SIGNING",null,null,null))==1,"NEP-145 pending nonce already exists");
        require(accounting.reservePreparation(op.tenantId(),request.id(),u.sender(),near(u.depositAtomic().add(p.maximumFeeYocto()))),"NEP-145 requires a funded tenant Gas account");
        return request;
    }
    public void sign(NearOperation op) {
        var request=intent(op).orElseThrow();var p=payload(op,request,json);
        var usage=usages.find(op.tenantId(),"ADDRESS_PREPARATION",request.id()).orElseThrow();
        require(usage.reservedAmount().compareTo(near(p.unsigned().depositAtomic().add(p.maximumFeeYocto())))==0
                && (request.state()==MpcSignRequest.State.COMPLETED || "RESERVED".equals(usage.status())),"NEP-145 Gas reservation must commit before signing");
        signing.awaitSignature(op.tenantId(),request.id(),nodes);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcNearTransactionCodec.Signed commit(NearOperation op) {
        var request=intent(op).orElseThrow();requests.lock(op.tenantId(),request.id()).orElseThrow();var p=payload(op,request,json);
        payments.lockRegistrationOwner(op,p.parentSender(),p.payment());var signed=selected(op,request,p);var row=transactions.find(signed.txHash()).orElseThrow();
        String raw=json.writeValueAsString(signed);
        if(row.getRawPayload()!=null)require(raw.equals(row.getRawPayload()),"NEP-145 durable wire differs from original selection");
        else require(transactions.bindSigned(signed.txHash(),raw)==1,"NEP-145 raw/hash binding raced");return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(NearOperation op,NearStorageReceipt.Proof observed,long latest,int confirmations) {
        var request=intent(op).orElseThrow();requests.lock(op.tenantId(),request.id()).orElseThrow();var p=payload(op,request,json);
        var signed=selected(op,request,p);var row=transactions.find(signed.txHash()).orElseThrow();
        require(json.writeValueAsString(signed).equals(row.getRawPayload()),"NEP-145 receipt has no original durable selection");
        var proof=row.getExecutionReceipt()==null?Objects.requireNonNull(observed):json.readValue(row.getExecutionReceipt(),NearStorageReceipt.Proof.class);
        var outcome=NearStorageReceipt.verify(proof,p.unsigned(),signed);
        var previousUsage=usages.find(op.tenantId(),"ADDRESS_PREPARATION",request.id()).orElseThrow();
        if(Set.of("CONFIRMED","FAILED").contains(row.getStatus()) && "SETTLED".equals(previousUsage.status())) {
            require(previousUsage.actualAmount().compareTo(near(outcome.expenseYocto()))==0,"NEP-145 settled expense differs");return outcome.registered();
        }
        payments.lockRegistrationOwner(op,p.parentSender(),p.payment());
        boolean finalized=latest>=outcome.blockHeight() && latest-outcome.blockHeight()+1>=Math.max(1,confirmations);
        transactions.upsert(record(p,signed.txHash(),finalized?(outcome.registered()?"CONFIRMED":"FAILED"):"CONFIRMING",row.getRawPayload(),json.writeValueAsString(proof),outcome));
        if(!finalized)return false;
        var usage=accounting.settle(op.tenantId(),"ADDRESS_PREPARATION",request.id(),near(outcome.expenseYocto()),"NEAR_STORAGE_FINAL_RECEIPT",signed.txHash());
        if(!"SETTLED".equals(usage.status()))return false;
        if(!outcome.registered())failParent(op);
        return outcome.registered();
    }
    public Optional<NearStorageReceipt.Proof> proof(NearOperation op) {
        return intent(op).map(r->MpcNearTransactionCodec.transactionHash(payload(op,r,json).unsigned())).flatMap(transactions::find)
                .map(NearTransactionRecord::getExecutionReceipt).map(raw->json.readValue(raw,NearStorageReceipt.Proof.class));
    }
    private MpcNearTransactionCodec.Signed selected(NearOperation op,MpcSignRequest request,NearRegistrationPayload p) {
        require(request.state()==MpcSignRequest.State.COMPLETED && signing.requireKey(op.tenantId(),request.command().keyId()).publicKeyHex().equals(p.unsigned().publicKeyHex()),"NEP-145 original joint result differs");
        return MpcNearTransactionCodec.assemble(p.unsigned(),signing.signature(op.tenantId(),request.id()).orElseThrow());
    }
    private void failParent(NearOperation op) {
        require(payments.intent(op).isEmpty(),"NEAR payment already has an accepted signature");String reason="final NEP-145 registration failure; attached deposit refunded; network fee charged to tenant Gas";
        if(op.kind()==NearOperation.Kind.WITHDRAWAL) {
            var order=orders.lockForSettlement(op.tenantId(),"NEAR",op.reference()).orElseThrow();
            require(order.getTxHash()==null && balances.release("NEAR",order.getAssetSymbol(),order.getDebitAccountId(),order.getAmount().add(order.getFee()),op.tenantId()),"NEP-145 failed parent principal is inconsistent");
            require(orders.failUnsignedPreparation(op.tenantId(),"NEAR",op.reference(),reason)==1,"NEP-145 failed parent transition raced");
        } else require(collections.failUnsignedPreparation(op.tenantId(),"NEAR",op.reference(),reason)==1,"NEP-145 failed collection transition raced");
    }
    private static NearTransactionRecord record(NearRegistrationPayload p,String hash,String state,String raw,String proof,NearStorageReceipt.Outcome outcome) {
        return NearTransactionRecord.builder().chain("NEAR").txHash(hash).actionIndex(0L).sender(p.unsigned().sender()).receiver(p.unsigned().contract())
                .assetSymbol("NEAR").amount(near(p.unsigned().depositAtomic())).status(state).rawPayload(raw).executionReceipt(proof)
                .blockHeight(outcome==null?null:outcome.blockHeight()).gasBurnt(outcome==null?null:outcome.gasBurnt()).feeYocto(outcome==null?null:outcome.expenseYocto()).build();
    }
    private static BigDecimal near(BigInteger amount){return new BigDecimal(amount).movePointLeft(24);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

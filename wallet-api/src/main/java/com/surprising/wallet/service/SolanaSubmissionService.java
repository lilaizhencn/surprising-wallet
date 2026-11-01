package com.surprising.wallet.service;

import com.surprising.wallet.common.signing.SolanaSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.SolanaTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;
import com.surprising.wallet.chain.solana.SolanaExecutionReceipt;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every required Ed25519 slot shares the fixed message; selected raw/hash precede network calls. */
@Service
@RequiredArgsConstructor
public class SolanaSubmissionService {
    private static final HexFormat HEX=HexFormat.of();
    private static final String SOURCE_SLOT="transaction",PAYER_SLOT="fee_payer",ENCODING="SOLANA_TX_JSON";
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final SolanaTransactionRepository transactions;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final TokenConfigRepository tokens;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyAddressRepository custodyAddresses;
    private final ObjectMapper json;
    private final WithdrawalSettlementService settlements;
    public enum Kind { WITHDRAWAL,COLLECTION }
    public record Operation(UUID tenantId,String reference,Kind kind) {
        public Operation {Objects.requireNonNull(tenantId);Objects.requireNonNull(kind);if(reference==null||reference.isBlank())throw new IllegalArgumentException("Solana business reference required");}
    }
    public record Payment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
    public Optional<MpcSignRequest> intent(Operation operation) {return request(operation,SOURCE_SLOT);}
    private Optional<MpcSignRequest> request(Operation operation,String slot) {
        return requests.findBusiness(operation.tenantId(),ChainType.SOLANA,MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),slot);
    }
    public SolanaSigningPayload payload(MpcSignRequest request) {
        require(request.command().chain()==ChainType.SOLANA && ENCODING.equals(request.command().payloadEncoding()),"Solana intent encoding differs");
        var payload=json.readValue(HEX.parseHex(request.command().payloadHex()),SolanaSigningPayload.class);
        require(payload.unsigned().messageHex().equals(request.command().preimageHex()),"Solana signature preimage differs from the fixed raw message");return payload;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public Payment payment(Operation operation) {var owner=lockOwner(operation);return new Payment(owner.symbol(),owner.recipient(),owner.amount(),owner.fee());}

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSignRequest reserve(Operation operation,ChainAddressRecord source,SolanaSigningPayload draft) {
        var owner=lockOwner(operation);
        require(operation.tenantId().equals(source.getTenantId()) && "SOLANA".equals(source.getChain()) && owner.sender().equals(source.getAddress()),"Solana source differs from owned business");
        var previous=intent(operation);
        if(previous.isPresent()) {
            require(previous.get().command().keyId().equals(source.getMpcKeyId()),"Solana source key changed");validate(operation,owner,payload(previous.get()));return previous.get();
        }
        require(owner.hash()==null && "SIGNING".equals(owner.state()),"Solana owner is not available for signing");
        validate(operation,owner,draft);
        var profile=profiles.findAccountByChain("SOLANA").orElseThrow();
        var signers=new LinkedHashMap<UUID,ChainAddressRecord>();signers.put(Objects.requireNonNull(source.getMpcKeyId()),source);
        CustodyGasAccountRepository.Definition gas=null;
        if(draft.mint()==null)require(source.getAddress().equals(draft.feePayer()),"Native SOL fee payer differs from source");
        else {
            var token=tokens.findDefinition("SOLANA",profile.getNetwork(),owner.symbol()).orElseThrow();
            require(token.getContractAddress().equals(draft.mint()) && token.getDecimals()==draft.decimals(),"Solana mint or precision differs from enabled asset");
            gas=gasAccounts.findByChain(operation.tenantId(),"SOLANA").filter(row->"ACTIVE".equals(row.status())).orElseThrow();
            var custody=custodyAddresses.require(operation.tenantId(),gas.custodyAddressId());
            var payer=addresses.findOwned(operation.tenantId(),custody.chainAddressId()).orElseThrow();
            require("ACTIVE".equals(custody.status()) && Boolean.TRUE.equals(payer.getEnabled()) && payer.getAddress().equals(draft.feePayer()),"Solana fee payer differs from active tenant Gas account");
            signers.putIfAbsent(Objects.requireNonNull(payer.getMpcKeyId()),payer);
        }
        var keys=new HashMap<String,MpcKey>();
        // Source and payer may exchange roles; a fixed lock order prevents a key/account deadlock.
        for(var address:signers.values().stream().sorted(Comparator.comparing(row->row.getMpcKeyId().toString())).toList()) {
            var key=signing.lockActiveKey(operation.tenantId(),address.getMpcKeyId(),ChainType.SOLANA);
            require(operation.tenantId().equals(address.getTenantId()) && address.getAddress().equals(MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex())))
                    && key.userId()==address.getUserId() && key.biz()==address.getBiz() && key.addressIndex()==address.getAddressIndex(),"Solana signer differs from owned joint key");
            keys.put(address.getAddress(),key);
        }
        if(gas!=null) {
            var locked=gasAccounts.lockForChain(operation.tenantId(),"SOLANA").orElseThrow();
            require(locked.id().equals(gas.id()) && locked.custodyAddressId().equals(gas.custodyAddressId()) && "ACTIVE".equals(locked.status()),"Solana Gas account changed before signing");
        }
        if(operation.kind()==Kind.COLLECTION)require(addresses.findEnabledByTenantAndAddress(operation.tenantId(),"SOLANA",owner.recipient()).orElseThrow().getMpcKeyId()!=null,"Solana collector has no tenant joint key");
        String encoded=HEX.formatHex(json.writeValueAsBytes(draft));
        for(String signer:MpcSolanaTransactionCodec.restore(draft.unsigned()).signers()) {
            var key=Objects.requireNonNull(keys.get(signer),"fixed Solana signer has no owned key");
            signing.reserveSignature(new MpcSignCommand(operation.tenantId(),key.id(),key.activeEpoch(),ChainType.SOLANA,
                    MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),signer.equals(owner.sender())?SOURCE_SLOT:PAYER_SLOT,
                    MpcSignCommand.Purpose.TRANSACTION,ENCODING,encoded,draft.unsigned().messageHex()),nodes.online());
        }
        return intent(operation).orElseThrow();
    }
    public void sign(Operation operation,UUID parentId) {
        var parent=intent(operation).orElseThrow();require(parent.id().equals(parentId),"Solana parent request differs");
        for(var slot:slots(operation,parent))signing.awaitSignature(operation.tenantId(),slot.id(),nodes);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public MpcSolanaTransactionCodec.Signed commit(Operation operation,UUID parentId) {
        var owner=lockOwner(operation);var parent=intent(operation).orElseThrow();require(parent.id().equals(parentId),"Solana parent request differs");
        var payload=payload(parent);validate(operation,owner,payload);var signed=selected(operation,parent);
        var existing=transactions.findSigned(signed.signature());
        if(existing.isPresent())require(signed.transactionBase64().equals(existing.get().getRawPayload()),"Solana selected raw changed");
        else {
            require(owner.hash()==null && ("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state())),"Solana owner cannot bind raw");
            transactions.upsert(record(owner,payload,signed,"PREPARED",null,0,0));
        }
        if(owner.hash()!=null)require(owner.hash().equals(signed.signature()),"Solana owner signature differs from selected transaction");
        else require((operation.kind()==Kind.WITHDRAWAL?orders.bindPrepared(operation.tenantId(),"SOLANA",operation.reference(),signed.signature())
                :collections.bindPrepared(operation.tenantId(),"SOLANA",operation.reference(),signed.signature()))==1,"Solana raw/business commit raced");
        return signed;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void observed(Operation operation,String signature) {
        var owner=lockOwner(operation);require(signature.equals(owner.hash()) && intent(operation).isPresent(),"Solana observed signature differs from owned intent");transactions.markSubmitted(signature);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void restoreSent(Operation operation,String signature) {
        var owner=lockOwner(operation);require(signature.equals(owner.hash()) && intent(operation).isPresent(),"Solana recovery owner differs");
        if(!("SIGNING".equals(owner.state())||"BROADCAST_UNKNOWN".equals(owner.state())))return;
        require((operation.kind()==Kind.WITHDRAWAL?orders.recoverPreparedSent(operation.tenantId(),"SOLANA",operation.reference(),signature)
                :collections.recoverPreparedSent(operation.tenantId(),"SOLANA",operation.reference(),signature))==1,"Solana SENT recovery raced");transactions.markSubmitted(signature);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public void unknown(Operation operation,String reason) {
        var owner=lockOwner(operation);if(!"SIGNING".equals(owner.state()))return;
        if(operation.kind()==Kind.WITHDRAWAL)orders.markBroadcastUnknown(operation.tenantId(),"SOLANA",operation.reference(),owner.sender(),reason);
        else collections.markSigningUnknown(operation.tenantId(),"SOLANA",operation.reference(),reason);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public int releaseUnsigned(Operation operation) {
        var owner=lockOwner(operation);if(owner.hash()!=null || intent(operation).isPresent())return 0;
        return operation.kind()==Kind.WITHDRAWAL?orders.recoverUnsignedDurable(operation.tenantId(),"SOLANA",operation.reference())
                :collections.recoverUnsignedDurable(operation.tenantId(),"SOLANA",operation.reference());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Throwable.class)
    public boolean settle(Operation operation,JsonNode receipt,JsonNode status,long latestSlot,int requiredConfirmations) {
        var owner=lockOwner(operation);var parent=intent(operation).orElseThrow();var payload=payload(parent);validate(operation,owner,payload);
        var signed=selected(operation,parent);require(signed.signature().equals(owner.hash()),"Solana receipt differs from owned signature");
        var outcome=SolanaExecutionReceipt.verify(receipt,status,payload,signed);
        if(latestSlot<outcome.slot())return false;
        long confirmations=latestSlot-outcome.slot()+1;if(confirmations<Math.max(1,requiredConfirmations))return false;
        if("CONFIRMED".equals(owner.state())||"FAILED".equals(owner.state()))return false;
        require("SENT".equals(owner.state())||"CONFIRMING".equals(owner.state()),"Solana business is not confirmable");
        require(transactions.findSigned(signed.signature()).isPresent(),"Solana receipt has no durable raw");
        transactions.upsert(record(owner,payload,signed,outcome.paid()?"CONFIRMED":"FAILED",outcome.slot(),(int)Math.min(Integer.MAX_VALUE,confirmations),outcome.costLamports()));
        if(operation.kind()==Kind.WITHDRAWAL)return outcome.paid()?settlements.confirm(operation.tenantId(),"SOLANA",operation.reference(),signed.signature())
                :settlements.fail(operation.tenantId(),"SOLANA",operation.reference(),signed.signature(),"confirmed Solana execution failure");
        require(collections.updateAmounts(operation.tenantId(),"SOLANA",operation.reference(),owner.amount(),BigDecimal.valueOf(outcome.costLamports()).movePointLeft(9))==1,"Solana collection cost update raced");
        require((outcome.paid()?collections.markConfirmed(operation.tenantId(),"SOLANA",operation.reference(),signed.signature())
                :collections.updateStatus(operation.tenantId(),"SOLANA",operation.reference(),"FAILED",signed.signature(),"confirmed Solana execution failure",null))==1,"Solana collection settlement raced");return true;
    }
    private List<MpcSignRequest> slots(Operation operation,MpcSignRequest parent) {
        var payload=payload(parent);String source=new org.p2p.solanaj.core.PublicKey(HEX.parseHex(signing.requireKey(operation.tenantId(),parent.command().keyId()).publicKeyHex())).toBase58();
        var result=new ArrayList<MpcSignRequest>();
        for(String signer:MpcSolanaTransactionCodec.restore(payload.unsigned()).signers()) {
            var slot=request(operation,signer.equals(source)?SOURCE_SLOT:PAYER_SLOT).orElseThrow();
            require(slot.command().payloadHex().equals(parent.command().payloadHex()) && slot.command().preimageHex().equals(parent.command().preimageHex()),"Solana signer slots belong to different messages");
            var key=signing.requireKey(operation.tenantId(),slot.command().keyId());
            require(new org.p2p.solanaj.core.PublicKey(HEX.parseHex(key.publicKeyHex())).toBase58().equals(signer),"Solana signer slot key differs from the fixed header");result.add(slot);
        }
        return result;
    }
    private MpcSolanaTransactionCodec.Signed selected(Operation operation,MpcSignRequest parent) {
        var signatures=new HashMap<String,byte[]>();
        for(var request:slots(operation,parent)) {
            require(request.state()==MpcSignRequest.State.COMPLETED,"Solana required signature is not completed");
            String address=new org.p2p.solanaj.core.PublicKey(HEX.parseHex(signing.requireKey(operation.tenantId(),request.command().keyId()).publicKeyHex())).toBase58();
            signatures.put(address,signing.signature(operation.tenantId(),request.id()).orElseThrow());
        }
        return MpcSolanaTransactionCodec.assemble(payload(parent).unsigned(),signatures);
    }
    public static String memo(Operation operation) {
        return HEX.formatHex(org.bitcoinj.base.Sha256Hash.hash(("surprising-wallet:solana:v1\n"+operation.tenantId()+"\n"+operation.kind()+"\n"+operation.reference()).getBytes(StandardCharsets.UTF_8)));
    }
    private static void validate(Operation operation,Owner owner,SolanaSigningPayload payload) {
        require(payload!=null && owner.amount().signum()>0 && payload.amount()!=null && owner.amount().compareTo(payload.amount())==0
                && owner.fee().compareTo(payload.customerFee())==0 && owner.symbol().equals(payload.symbol()) && owner.sender().equals(payload.sender())
                && owner.recipient().equals(payload.recipient()) && payload.lastValidBlockHeight()>0
                && payload.decimals()>=0 && payload.decimals()<=18 && !owner.sender().equals(owner.recipient()),"Solana payload differs from owned payment");
        require(("SOL".equals(payload.symbol()) && payload.mint()==null && payload.decimals()==9) || (!"SOL".equals(payload.symbol()) && payload.mint()!=null),"Solana asset kind differs");
        var parsed=MpcSolanaTransactionCodec.restore(payload.unsigned());
        var expected=MpcSolanaTransactionCodec.payment(owner.sender(),payload.feePayer(),owner.recipient(),payload.mint(),owner.amount().movePointRight(payload.decimals()).longValueExact(),payload.decimals(),parsed.blockhash(),memo(operation));
        require(expected.equals(payload.unsigned()),"Solana instructions differ from the exact owned payment");
        var signers=new HashSet<>(List.of(owner.sender(),payload.feePayer()));require(signers.equals(new HashSet<>(parsed.signers())),"Solana fixed message has unrelated signers");
    }
    private Owner lockOwner(Operation operation) {
        if(operation.kind()==Kind.WITHDRAWAL){var row=orders.lockForSettlement(operation.tenantId(),"SOLANA",operation.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),row.getFee(),row.getTxHash());}
        var row=collections.lockForSubmission(operation.tenantId(),"SOLANA",operation.reference()).orElseThrow();return new Owner(row.getStatus(),row.getFromAddress(),row.getToAddress(),row.getAssetSymbol(),row.getAmount(),BigDecimal.ZERO,row.getTxHash());
    }
    private static SolanaTransactionRecord record(Owner owner,SolanaSigningPayload payload,MpcSolanaTransactionCodec.Signed signed,String state,Long slot,int confirmations,long fee) {
        return SolanaTransactionRecord.builder().chain("SOLANA").signature(signed.signature()).fromAddress(owner.sender()).toAddress(owner.recipient()).assetSymbol(payload.symbol()).mintAddress(payload.mint())
                .amount(payload.amount()).feeLamports(fee).slot(slot).confirmations(confirmations).status(state).rawPayload(signed.transactionBase64()).build();
    }
    private record Owner(String state,String sender,String recipient,String symbol,BigDecimal amount,BigDecimal fee,String hash) { }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

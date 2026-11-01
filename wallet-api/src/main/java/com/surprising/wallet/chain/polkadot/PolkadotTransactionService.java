package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.PolkadotSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.PolkadotSubmissionService.Operation;
import com.surprising.wallet.service.PolkadotSubmissionService.Kind;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Public-only SCALE preparation; recovery retains the original role, nonce, era and witness. */
@Service
@RequiredArgsConstructor
public class PolkadotTransactionService {
    private final PolkadotRuntimeClient rpc;
    private final PolkadotSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final ChainAddressRepository addresses;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final CustodyGasService gas;
    @NonNull private final WalletRuntimeConfigService runtime;

    public String send(Operation op,ChainAddressRecord source) {
        runtime.requireTaskEnabled("DOT",WalletRuntimeConfigService.TASK_WITHDRAW,"DOT withdrawal");
        require(op.kind()==Kind.WITHDRAWAL,"DOT withdrawal operation required");return transfer(op,source);
    }
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("DOT",WalletRuntimeConfigService.TASK_COLLECTION,"DOT collection");
        var previous=collections.findTxHash(tenant,"DOT",reference);if(previous.isPresent())return previous.get();
        require(collections.claimSigningDurable(tenant,"DOT",reference)==1,"DOT collection is claimed; recovery owns the intent");
        var op=new Operation(tenant,reference,Kind.COLLECTION);
        try {String hash=transfer(op,source);submissions.restoreSent(op,hash);return hash;}
        catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    private String transfer(Operation op,ChainAddressRecord source) {
        PolkadotSigningPayload draft=null;
        if(submissions.intent(op).isEmpty()) {
            var payment=submissions.payment(op);String assetId=null;int decimals=nativeDecimals();Role role=Role.ASSET_HUB;
            if(!"DOT".equals(payment.symbol())) {
                var profile=profiles.findAccountByChain("DOT").orElseThrow();var token=tokens.findDefinition("DOT",profile.getNetwork(),payment.symbol()).orElseThrow();
                assetId=token.getContractAddress();decimals=token.getDecimals();role=Role.ASSET_HUB;
                ensureAssetHubGas(op,source,payment.recipient(),payment.amount().movePointRight(decimals).toBigIntegerExact(),assetId);
            }
            draft=prepare(source,payment,role,assetId,decimals);
        }
        var request=submissions.reserve(op,source,draft);submissions.sign(op,request.id());
        return broadcast(op,submissions.commit(op,request.id()),submissions.payload(request));
    }
    private PolkadotSigningPayload prepare(ChainAddressRecord source,PolkadotSubmissionService.Payment payment,Role role,String assetId,int decimals) {
        var info=rpc.networkInfo(role,source.getAddress(),assetId);var profile=profiles.findAccountByChain("DOT").orElseThrow();
        verifyMetadata(info,profile.getNetwork(),role);
        require(info.path("nativeDecimals").asInt(-1)==nativeDecimals() && info.path("decimals").asInt(-1)==decimals,"DOT runtime/configured asset precision differs");
        var amount=payment.amount().movePointRight(decimals).toBigIntegerExact();
        BigInteger budget=feeBudget(role,source.getAddress(),payment.recipient(),amount,assetId),ed=atomic(info,"existentialDeposit");
        require(ed.signum()>0 && spendable(info).compareTo(ed.add(budget).add(assetId==null?amount:BigInteger.ZERO))>=0,"DOT payment would consume minimum account balance or native fee reserve");
        if(assetId!=null)require(rpc.assetBalance(assetId,source.getAddress()).compareTo(amount)>=0,"DOT asset principal exceeds on-chain balance");
        List<String> extensions=new ArrayList<>();info.path("extensions").forEach(value->extensions.add(value.asText()));
        var u=new MpcPolkadotTransactionCodec.Unsigned(profile.getNetwork(),role,info.path("genesisHash").asText(),info.path("specVersion").asLong(),info.path("transactionVersion").asLong(),
                source.getAddress(),submissions.publicKey(source),payment.recipient(),amount,assetId,info.path("nonce").asLong(-1),64,info.path("blockHeight").asLong(-1),info.path("blockHash").asText(),extensions,
                new MpcPolkadotTransactionCodec.CallIndices(info.path("callIndices").path("keepAlive").asText(),info.path("callIndices").path("allowDeath").asText()),assetId==null);
        return new PolkadotSigningPayload(payment.symbol(),payment.amount(),payment.customerFee(),decimals,nativeDecimals(),budget,ed,u);
    }
    private void ensureAssetHubGas(Operation business,ChainAddressRecord target,String recipient,BigInteger amount,String assetId) {
        var funding=funding(business);
        if(submissions.intent(funding).isPresent())finishFunding(funding);
        var info=rpc.networkInfo(Role.ASSET_HUB,target.getAddress(),assetId);BigInteger budget=feeBudget(Role.ASSET_HUB,target.getAddress(),recipient,amount,assetId);
        BigInteger required=atomic(info,"existentialDeposit").add(budget);
        if(spendable(info).compareTo(required)>=0)return;
        require(submissions.intent(funding).isEmpty(),"DOT original Gas funding completed but reserve is still insufficient; reconcile its receipt");
        var account=gas.findGasAccount(business.tenantId(),"DOT").filter(row->"ACTIVE".equals(row.status())).orElseThrow();
        var source=addresses.findEnabledByTenantAndAddress(business.tenantId(),"DOT",account.address()).orElseThrow();
        require(source.getMpcKeyId()!=null && !source.getAddress().equals(target.getAddress()),"DOT Asset Hub Gas source must be a different owned joint address");
        BigInteger topUp=atomic(info,"existentialDeposit").add(budget.multiply(BigInteger.valueOf(8))).subtract(spendable(info)).max(BigInteger.ZERO);
        var payment=new PolkadotSubmissionService.Payment("DOT",target.getAddress(),new BigDecimal(topUp).movePointLeft(nativeDecimals()),BigDecimal.ZERO);
        submissions.reserve(funding,source,prepare(source,payment,Role.ASSET_HUB,null,nativeDecimals()));finishFunding(funding);
        require(spendable(rpc.networkInfo(Role.ASSET_HUB,target.getAddress(),assetId)).compareTo(required)>=0,"DOT finalized Gas funding did not reach required native reserve");
    }
    private void finishFunding(Operation op) {
        if(!submissions.terminal(op)) {
            recoverOriginal(op);
            for(int attempt=0;attempt<20 && !submissions.terminal(op);attempt++) {
                confirm(op);
                if(!submissions.terminal(op))try{Thread.sleep(1000);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("DOT funding interrupted",error);}
            }
        }
        require(submissions.terminal(op),"DOT Gas funding is pending; retry recovers its original intent");
        require(submissions.paid(op),"DOT Gas funding failed in a finalized block");
    }
    public boolean recover(Operation op) {
        var funding=funding(op);if(submissions.intent(funding).isPresent())finishFunding(funding);
        return recoverOriginal(op);
    }
    private boolean recoverOriginal(Operation op) {
        var request=submissions.intent(op);if(request.isEmpty())return false;
        submissions.sign(op,request.get().id());var signed=submissions.commit(op,request.get().id());
        String hash=broadcast(op,signed,submissions.payload(request.get()));submissions.restoreSent(op,hash);return true;
    }
    public int releaseUnsigned(Operation op){return submissions.releaseUnsigned(op);}
    private String broadcast(Operation op,MpcPolkadotTransactionCodec.Signed selected,PolkadotSigningPayload p) {
        var proof=rpc.proof(p.unsigned(),selected.txHash());requireIdentity(p,proof);
        if(proof.path("found").asBoolean(false))PolkadotExecutionReceipt.verify(proof,p.unsigned(),selected);
        else if(proof.path("pending").asBoolean(false))require(selected.extrinsicHex().equals(proof.path("pendingExtrinsicHex").asText()),"DOT pending wire differs from selected witness");
        else {
            require(proof.path("finalizedHeight").asLong(-1)<p.unsigned().validUntilHeight(),"DOT original mortal era expired; retain payment for reconciliation");
            var info=rpc.networkInfo(p.unsigned().role(),p.unsigned().sender(),p.unsigned().assetId());requireIdentity(p,info);
            require(info.path("specVersion").asLong(-1)==p.unsigned().specVersion() && info.path("transactionVersion").asLong(-1)==p.unsigned().transactionVersion(),"DOT runtime changed; retain original payment for reconciliation");
            require(selected.txHash().equals(rpc.broadcast(p.unsigned().role(),selected.extrinsicHex())),"DOT node returned a different selected extrinsic hash");
        }
        submissions.observed(op,selected.txHash());return selected.txHash();
    }
    public boolean confirmWithdrawal(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL));}
    public boolean confirmCollection(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.COLLECTION));}
    private boolean confirm(Operation op) {
        var request=submissions.intent(op).orElseThrow();var p=submissions.payload(request);var head=rpc.finalized(p.unsigned().role());requireIdentity(p,head);
        var proof=submissions.proof(op).orElseGet(()->rpc.proof(p.unsigned(),submissions.commit(op,request.id()).txHash()));requireIdentity(p,proof);
        if(!proof.path("found").asBoolean(true))return false;
        return submissions.settle(op,proof,head.path("height").asLong(-1),profiles.findAccountByChain("DOT").orElseThrow().getWithdrawConfirmations());
    }
    public BigDecimal collectableNativeAmount(String address,BigDecimal candidate,BigDecimal feeReserve) {
        var info=rpc.networkInfo(Role.ASSET_HUB,address,null);int decimals=nativeDecimals();require(info.path("nativeDecimals").asInt(-1)==decimals,"DOT native precision differs");
        BigInteger limit=spendable(info).subtract(atomic(info,"existentialDeposit")).max(BigInteger.ZERO);
        BigDecimal requested=candidate.subtract(feeReserve).max(BigDecimal.ZERO);
        BigInteger upper=requested.movePointRight(decimals).toBigIntegerExact().min(limit);
        if(upper.signum()==0)return BigDecimal.ZERO;
        // Quote the actual upper-bound SCALE amount length; one-planck quotes underprice full sweeps.
        // Three quotes leave headroom above the two-quote admission budget if the multiplier moves.
        BigInteger quote=rpc.paymentInfo(Role.ASSET_HUB,address,address,upper,null);
        require(quote.signum()>0,"DOT collection fee quote is unavailable");
        BigInteger fee=quote.multiply(BigInteger.valueOf(3)).max(feeReserve.movePointRight(decimals).setScale(0,RoundingMode.CEILING).toBigIntegerExact());
        BigDecimal available=new BigDecimal(limit.subtract(fee).max(BigInteger.ZERO)).movePointLeft(decimals);
        return requested.min(available);
    }
    private int nativeDecimals(){return assets.findActiveDefinition("DOT","DOT").orElseThrow().getDecimals();}
    private BigInteger feeBudget(Role role,String source,String recipient,BigInteger amount,String assetId){BigInteger quote=rpc.paymentInfo(role,source,recipient,amount,assetId);require(quote.signum()>0,"DOT fee quote is unavailable");return quote.multiply(BigInteger.TWO);}
    private static BigInteger atomic(JsonNode info,String field){String value=info.path(field).asText();require(value.matches("0|[1-9][0-9]{0,38}"),"DOT native balance metadata is unavailable");return new BigInteger(value);}
    private static BigInteger spendable(JsonNode info){return atomic(info,"nativeBalance").subtract(atomic(info,"frozenBalance")).max(BigInteger.ZERO);}
    private static void requireIdentity(PolkadotSigningPayload p,JsonNode result){require(p.unsigned().genesisHash().equals(result.path("genesisHash").asText()),"DOT RPC genesis differs from fixed role intent");}
    static void verifyMetadata(JsonNode info,String network,Role role) {
        require(info.path("extrinsicVersion").asInt(-1)==4 && info.path("extensionVersion").asInt(-1)==0,"DOT signing requires the explicit signed V4 extension pipeline 0");
        String name=info.path("specName").asText();
        boolean local=Set.of("localnet","regtest").contains(network);
        boolean recognized=switch(network){case "mainnet","polkadot"->role==Role.RELAY?name.equals("polkadot"):Set.of("statemint","asset-hub-polkadot").contains(name);case "westend"->role==Role.RELAY?name.equals("westend"):Set.of("westmint","asset-hub-westend").contains(name);case "rococo"->role==Role.RELAY?name.equals("rococo"):Set.of("rockmine","asset-hub-rococo").contains(name);default->false;};
        require(recognized || (local && !Set.of("polkadot","statemint","asset-hub-polkadot").contains(name) && !name.isBlank()),"DOT configured network differs from runtime role");
        Map<String,String> extra=new LinkedHashMap<>(Map.of("era","ExtrinsicEra","nonce","Compact<Index>","tip","Compact<Balance>"));
        boolean metadata=false,assetFee=false;for(var value:info.path("extensions")){metadata|=value.asText().equals("CheckMetadataHash");assetFee|=value.asText().equals("ChargeAssetTxPayment");}
        if(metadata)extra.put("mode","u8");if(assetFee)extra.put("assetId","TAssetConversion");
        Map<String,String> additional=new LinkedHashMap<>(Map.of("specVersion","u32","transactionVersion","u32","genesisHash","Hash","blockHash","Hash"));if(metadata)additional.put("metadataHash","Option<[u8;32]>");
        require(fields(info.path("extraFields")).equals(extra) && fields(info.path("additionalSignedFields")).equals(additional),"DOT runtime extension field types differ from reviewed SCALE encoding");
    }
    private static Map<String,String> fields(JsonNode node){Map<String,String> result=new HashMap<>();node.properties().forEach(entry->result.put(entry.getKey(),entry.getValue().asText()));return result;}
    private static Operation funding(Operation op){try{String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((op.kind()+":"+op.reference()).getBytes(StandardCharsets.UTF_8)));return new Operation(op.tenantId(),"assethub-gas:"+digest,Kind.FUNDING);}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

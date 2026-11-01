package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.signing.MoneroSigningPayload;

import com.surprising.wallet.common.chain.ChainScanPoint;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.MoneroSubmissionService.Kind;
import com.surprising.wallet.service.MoneroSubmissionService.Operation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Native-only Monero payment workflow; Java never uses a private wallet RPC. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MoneroTransactionService {
    private final MoneroDaemonClient daemon;
    private final MoneroSubmissionService submissions;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final ChainAddressRepository addresses;
    private final CollectionRecordRepository collections;
    private final CustodyGasService gas;
    private final WalletRuntimeConfigService runtime;
    private final MpcAuthorizationService authorizations;

    public String send(Operation op,ChainAddressRecord source) {
        require(op.kind()==Kind.WITHDRAWAL,"Monero withdrawal operation required");
        if(submissions.intent(op).isPresent())return recoverOriginal(op);
        runtime.requireTaskEnabled("XMR",WalletRuntimeConfigService.TASK_WITHDRAW,"Monero withdrawal");
        return transfer(op,source);
    }
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        var op=new Operation(tenant,reference,Kind.COLLECTION);
        if(submissions.intent(op).isPresent())return recoverOriginal(op);
        runtime.requireTaskEnabled("XMR",WalletRuntimeConfigService.TASK_COLLECTION,"Monero collection");
        require(collections.claimSigningDurable(tenant,"XMR",reference)==1,"Monero collection already has a durable owner");
        return transfer(op,source);
    }
    private String transfer(Operation op,ChainAddressRecord source) {
        try {
            var payment=submissions.payment(op);var profile=profiles.findAccountByChain("XMR").orElseThrow();var network=daemon.network(profile.getNetwork());
            BigInteger cap=BigInteger.valueOf(profile.getDefaultFee()==null?0:profile.getDefaultFee()).max(BigInteger.valueOf(3_000_000_000L));
            var draft=new MoneroSigningPayload(network.name(),network.genesisHash(),source.getAddress(),payment.recipient(),payment.amount(),payment.customerFee(),cap,
                    Math.max(1,profile.getWithdrawConfirmations()));
            // Admit the main immutable payment before any Gas funding can leave the tenant's Gas account.
            submissions.reserve(op,source,draft);return recoverOriginal(op);
        } catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    public boolean recover(Operation op) {if(submissions.intent(op).isEmpty())return false;recoverOriginal(op);return true;}
    /** Accepted payments finish before admission switches, including when the entire chain is closed. */
    public void recoverObserved() {
        for(var op:submissions.pending()) {
            try{recoverOriginal(op);}catch(RuntimeException pending){log.debug("Monero original relay/signing pending: {}",op.reference());}
            try{confirm(op);}catch(RuntimeException pending){log.debug("Monero original confirmation pending: {}",op.reference());}
            finally{submissions.refreshed(op);}
        }
    }
    private String recoverOriginal(Operation op) {
        try {
            var request=submissions.intent(op).orElseThrow();var payload=submissions.payload(request);
            if(!submissions.terminal(op) && submissions.stored(op).orElseThrow().txHash()==null) {
                if(op.kind()!=Kind.FUNDING && !signing.hasStartedMoneroSignature(op.tenantId(),request.id()))
                    ensureFunds(op,source(op.tenantId(),request.command().keyId(),payload.sender()),payload);
                submissions.sign(op,request.id());
            }
            var selected=submissions.commit(op,request.id());
            if(!submissions.terminal(op))submissions.relay(op);
            return selected.transactionHash();
        } catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    private void ensureFunds(Operation business,ChainAddressRecord source,MoneroSigningPayload payload) {
        var funding=funding(business);var existing=submissions.intent(funding);
        if(existing.isPresent() && !submissions.terminal(funding)) {
            recoverOriginal(funding);confirm(funding);
            if(!submissions.terminal(funding))throw new MpcPendingException(existing.get().id(),"FUNDING_CONFIRMING");
        }
        var snapshot=observe(source,payload,null);var observation=snapshot.result().monero();
        if(business.kind()==Kind.COLLECTION)authorizations.planCollection(new com.surprising.wallet.common.approval.AutomaticParentPlan(business.tenantId(),source.getMpcKeyId(),"XMR",payload.network(),"XMR",business.reference(),payload.recipient(),payload.amount(),snapshot.request()));
        var balance=new BigInteger(observation.path("balanceAtomic").asText());
        var unlocked=new BigInteger(observation.path("unlockedBalanceAtomic").asText());
        var required=payload.amountAtomic().add(payload.maximumFeeAtomic()).add(BigInteger.ONE);
        if(unlocked.compareTo(required)>=0)return;
        var parent=submissions.intent(business).orElseThrow();
        if(balance.compareTo(required)>=0)throw new MpcPendingException(parent.id(),"FUNDS_LOCKED");
        require(balance.compareTo(payload.amountAtomic())>=0,"Monero owned source cannot cover the fixed payment principal");
        if(existing.isPresent())throw new MpcPendingException(existing.get().id(),"ORIGINAL_FUNDING_RECONCILIATION");
        var account=gas.findGasAccount(business.tenantId(),"XMR").filter(row->row.status().equals("ACTIVE")).orElseThrow();
        var gasSource=addresses.findEnabledByTenantAndAddress(business.tenantId(),"XMR",account.address()).orElseThrow();
        require(gasSource.getMpcKeyId()!=null && !gasSource.getAddress().equals(source.getAddress()),"Monero Gas source must be a different owned native key");
        var amount=required.subtract(balance);
        var draft=new MoneroSigningPayload(payload.network(),payload.genesisHash(),gasSource.getAddress(),source.getAddress(),new BigDecimal(amount).movePointLeft(12),
                BigDecimal.ZERO,payload.maximumFeeAtomic(),payload.requiredConfirmations());
        var gasSnapshot=observe(gasSource,draft,null);var available=new BigInteger(gasSnapshot.result().monero().path("unlockedBalanceAtomic").asText());
        require(available.compareTo(amount.add(draft.maximumFeeAtomic()).add(BigInteger.ONE))>=0,"Monero tenant Gas wallet cannot cover the original funding and fee");
        draft=new MoneroSigningPayload(draft.network(),draft.genesisHash(),draft.sender(),draft.recipient(),draft.amount(),draft.customerFee(),draft.maximumFeeAtomic(),draft.requiredConfirmations(),List.of(snapshot.request(),gasSnapshot.request()));
        var request=submissions.reserve(funding,gasSource,draft);recoverOriginal(funding);confirm(funding);
        throw new MpcPendingException(request.id(),"FUNDING_UNLOCK_PENDING");
    }
    public int releaseUnsigned(Operation op){return submissions.releaseUnsigned(op);}
    public boolean confirmWithdrawal(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL));}
    public boolean confirmCollection(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.COLLECTION));}
    private boolean confirm(Operation op) {
        if(submissions.terminal(op))return false;var request=submissions.intent(op).orElseThrow();
        if(submissions.stored(op).orElseThrow().txHash()==null)return false;
        var payload=submissions.payload(request);var selected=submissions.commit(op,request.id());
        return submissions.settle(op,observe(source(op.tenantId(),request.command().keyId(),payload.sender()),payload,selected.transactionHash()));
    }
    private MpcSigningService.MoneroSnapshot observe(ChainAddressRecord source,MoneroSigningPayload payload,String hash) {
        var network=daemon.network(payload.network());require(network.genesisHash().equals(payload.genesisHash()),"Monero daemon changed the original signing network");
        var header=daemon.header(payload.network(),network.height()-1);
        var anchor=new MoneroScanAnchorService.Anchor(payload.network(),payload.genesisHash(),new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
        return signing.observeMonero(source.getTenantId(),source.getMpcKeyId(),source.getAddress(),header.height(),anchor,hash,nodes);
    }
    private ChainAddressRecord source(UUID tenant,UUID key,String address) {
        return addresses.listMpcAddresses("XMR").stream().filter(row->row.getTenantId().equals(tenant) && row.getMpcKeyId().equals(key) && row.getAddress().equals(address)).findFirst().orElseThrow();
    }
    private static Operation funding(Operation op) {
        try{return new Operation(op.tenantId(),"xmr-gas:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((op.kind()+":"+op.reference()).getBytes(StandardCharsets.UTF_8))),Kind.FUNDING);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}

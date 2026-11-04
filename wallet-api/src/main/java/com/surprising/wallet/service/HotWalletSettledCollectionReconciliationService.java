package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.HotWalletSettledCollectionObservation;
import com.surprising.wallet.common.approval.HotWalletSettledCollectionRequest;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Releases a direct EVM collection hold only after the independent finalized receipt enters hot inventory. */
@Service
public class HotWalletSettledCollectionReconciliationService {
    private static final Logger LOG=LoggerFactory.getLogger(HotWalletSettledCollectionReconciliationService.class);
    private Long cursor;
    private final CollectionRecordRepository collections;
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final CustodyAuditRepository audits;
    private final WithdrawalApprovalClient approval;
    private final TransactionTemplate transaction;

    public HotWalletSettledCollectionReconciliationService(CollectionRecordRepository collections,
            CustodyHotWalletRepository wallets,CustodyHotWalletInventoryObservationRepository observations,
            CustodyAddressRepository addresses,ChainAddressRepository chainAddresses,
            ChainAssetRepository assets,TokenConfigRepository tokens,CustodyAuditRepository audits,
            WithdrawalApprovalClient approval,PlatformTransactionManager manager) {
        this.collections=collections;this.wallets=wallets;this.observations=observations;
        this.addresses=addresses;this.chainAddresses=chainAddresses;this.assets=assets;this.tokens=tokens;
        this.audits=audits;this.approval=approval;this.transaction=new TransactionTemplate(manager);
    }

    public record ReconciliationView(UUID walletId,String chain,String assetSymbol,String collectionNo,
            String txHash,long receiptBlockHeight,String receiptBlockHash,long observedBlockHeight,
            String observedBlockHash,BigDecimal remainingInboundExposure,boolean newlyAccounted) { }

    public ReconciliationView reconcile(CustodyPrincipal principal,UUID walletId,String collectionNo,String sourceIp) {
        if(principal==null || principal.tenantId()==null || !"TENANT_ADMIN".equals(principal.role())
                || !principal.hasScope("chains:write") || !principal.hasScope("assets:read"))
            throw new CustodyForbiddenException("tenant administrator chains:write and assets:read scopes required");
        return reconcileOriginal(principal.tenantId(),walletId,collectionNo,
                principal.actorType().name(),principal.actorId().toString(),sourceIp);
    }

    public synchronized void reconcilePending() {
        var pending=collections.listConfirmedUnaccountedAfter(cursor,20);
        if(pending.isEmpty() && cursor!=null) {
            cursor=null;
            pending=collections.listConfirmedUnaccountedAfter(null,20);
        }
        for(var row:pending) {
            cursor=row.id();
            try {
                if(!ChainType.valueOf(row.chain()).isEvm())continue;
                var target=wallets.list(row.tenantId(),row.chain()).stream()
                        .filter(wallet->"ACTIVE".equals(wallet.status())
                            && addresses.require(row.tenantId(),wallet.custodyAddressId())
                                .address().equalsIgnoreCase(row.toAddress()))
                        .findFirst().orElse(null);
                if(target!=null)reconcileOriginal(row.tenantId(),target.id(),row.collectionNo(),
                        "SYSTEM","finalized-collection-reconciliation",null);
            } catch(RuntimeException failure) {
                LOG.warn("Confirmed hot collection remains charged: tenant={}, collection={}, reason={}",
                        row.tenantId(),row.collectionNo(),failure.getMessage());
            }
        }
    }

    private ReconciliationView reconcileOriginal(UUID tenant,UUID walletId,String reference,
            String actorType,String actorId,String sourceIp) {
        if(tenant==null || walletId==null || reference==null || !reference.matches("[A-Za-z0-9_-]{1,96}"))
            throw new IllegalArgumentException("valid original collection reference required");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("independent finalized collection proof requires no caller transaction");
        var wallet=wallets.find(tenant,walletId).orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if(!ChainType.valueOf(wallet.chain()).isEvm())
            throw new IllegalStateException("finalized direct collection accounting requires an EVM wallet");
        var original=collections.findInboundAccounting(tenant,wallet.chain(),reference,false).orElseThrow();
        var source=sourceIdentity(original);
        requireOriginal(wallet,original,source.address());
        if(original.accountedAt()!=null)return view(wallet,original,false,null);
        if(!"ACTIVE".equals(wallet.status()))
            throw new IllegalStateException("only an active hot wallet may refresh collection inventory");
        var current=observations.find(tenant,walletId,original.assetSymbol()).orElseThrow();
        if(current.invalidatedAt()!=null || current.observedBlockHeight()==null
                || current.observedBlockHash()==null || !wallet.mpcKeyId().equals(current.keyId()))
            throw new IllegalStateException("active hot wallet needs safe anchored inventory before collection accounting");
        BigInteger atomic;
        try {atomic=original.amount().movePointRight(current.decimals()).toBigIntegerExact();}
        catch(ArithmeticException error) {throw new IllegalStateException("original collection precision differs",error);}
        var request=new HotWalletSettledCollectionRequest(tenant,wallet.chain(),wallet.network(),
                original.assetSymbol(),source.keyId(),original.fromAddress(),wallet.mpcKeyId(),
                original.toAddress(),original.txHash(),atomic);
        var proof=approval.settledDirectCollection(request);
        requireProof(wallet,original,source.keyId(),current,proof,atomic);
        return transaction.execute(status->{
            var lockedWallet=wallets.lock(tenant,walletId).orElseThrow();
            var locked=collections.findInboundAccounting(tenant,wallet.chain(),reference,true).orElseThrow();
            var lockedSource=sourceIdentity(locked);
            requireOriginal(lockedWallet,locked,lockedSource.address());
            if(locked.accountedAt()!=null)return view(lockedWallet,locked,false,proof);
            if(!"ACTIVE".equals(lockedWallet.status()) || !wallet.mpcKeyId().equals(lockedWallet.mpcKeyId())
                    || !wallet.network().equals(lockedWallet.network()) || !lockedSource.keyId().equals(source.keyId()))
                throw new IllegalStateException("collection ownership changed before finalized accounting");
            var latest=observations.find(tenant,walletId,locked.assetSymbol()).orElseThrow();
            requireProof(lockedWallet,locked,lockedSource.keyId(),latest,proof,atomic);
            var observed=new CustodyHotWalletInventoryObservationRepository.Observation(tenant,walletId,
                    wallet.chain(),locked.assetSymbol(),wallet.mpcKeyId(),proof.destinationAddress(),
                    proof.networkIdentity(),proof.decimals(),proof.nativeDecimals(),
                    proof.availableAtomic(),proof.nativeAtomic(),proof.observedAt(),
                    proof.collectionRuleId(),proof.collectionRuleFingerprint(),
                    proof.observedBlockHeight(),proof.observedBlockHash());
            if(observations.record(observed)!=1)
                throw new IllegalStateException("finalized receiving balance cannot replace newer inventory evidence");
            if(collections.markInboundAccounted(tenant,wallet.chain(),reference,proof.txHash(),
                    proof.receiptBlockHeight(),proof.receiptBlockHash(),proof.observedBlockHeight(),
                    proof.observedBlockHash())!=1)
                throw new IllegalStateException("confirmed collection accounting raced");
            audits.insert(UUID.randomUUID(),tenant,actorType,actorId,
                    "HOT_WALLET.CONFIRMED_COLLECTION_ACCOUNTED","HOT_WALLET",walletId.toString(),
                    sourceIp,"{\"collectionNo\":\""+reference+"\",\"txHash\":\""+proof.txHash()+"\"}");
            return view(lockedWallet,collections.findInboundAccounting(tenant,wallet.chain(),reference,false).orElseThrow(),
                    true,proof);
        });
    }

    private record Source(UUID keyId,String address) { }
    private Source sourceIdentity(CollectionRecordRepository.InboundAccounting row) {
        if(row.custodyAddressId()==null)throw new IllegalStateException("original collection source identity missing");
        var address=addresses.require(row.tenantId(),row.custodyAddressId());
        var owner=chainAddresses.findOwned(row.tenantId(),address.chainAddressId()).orElseThrow();
        if(owner.getMpcKeyId()==null || !row.chain().equals(address.chain())
                || !address.address().equalsIgnoreCase(owner.getAddress()))
            throw new IllegalStateException("original collection source key/address changed");
        return new Source(owner.getMpcKeyId(),address.address());
    }

    private void requireOriginal(CustodyHotWalletRepository.Wallet wallet,
            CollectionRecordRepository.InboundAccounting row,String source) {
        if(!wallet.tenantId().equals(row.tenantId()) || !wallet.chain().equals(row.chain())
                || !source.equalsIgnoreCase(row.fromAddress()) || row.amount()==null
                || row.amount().signum()<=0 || !"CONFIRMED".equals(row.status())
                || row.txHash()==null || !row.txHash().matches("0x[0-9a-fA-F]{64}")
                || !addresses.require(wallet.tenantId(),wallet.custodyAddressId()).address()
                        .equalsIgnoreCase(row.toAddress()))
            throw new IllegalStateException("confirmed collection differs from original owned direct transfer");
    }

    private void requireProof(CustodyHotWalletRepository.Wallet wallet,
            CollectionRecordRepository.InboundAccounting row,UUID sourceKey,
            CustodyHotWalletInventoryObservationRepository.Observation current,
            HotWalletSettledCollectionObservation proof,BigInteger atomic) {
        Instant now=Instant.now();
        if(proof==null || !wallet.tenantId().equals(proof.tenantId())
                || !wallet.mpcKeyId().equals(proof.destinationKeyId())
                || !sourceKey.equals(proof.sourceKeyId()) || !wallet.chain().equals(proof.chain())
                || !wallet.network().equals(proof.network()) || !row.assetSymbol().equals(proof.assetSymbol())
                || !row.fromAddress().equalsIgnoreCase(proof.sourceAddress())
                || !row.toAddress().equalsIgnoreCase(proof.destinationAddress())
                || !row.txHash().equalsIgnoreCase(proof.txHash()) || !atomic.equals(proof.amountAtomic())
                || !current.networkIdentity().equals(proof.networkIdentity())
                || current.decimals()!=proof.decimals() || current.nativeDecimals()!=proof.nativeDecimals()
                || !current.keyId().equals(proof.destinationKeyId())
                || !current.address().equalsIgnoreCase(proof.destinationAddress())
                || current.invalidatedAt()!=null || proof.availableAtomic()==null
                || proof.availableAtomic().signum()<0 || proof.nativeAtomic()==null
                || proof.nativeAtomic().signum()<0 || proof.receiptBlockHeight()<0
                || proof.observedBlockHeight()<proof.receiptBlockHeight()
                || proof.receiptBlockHash()==null || !proof.receiptBlockHash().matches("0x[0-9a-f]{64}")
                || proof.observedBlockHash()==null || !proof.observedBlockHash().matches("0x[0-9a-f]{64}")
                || proof.observedAt()==null || proof.observedAt().isBefore(now.minusSeconds(60))
                || proof.observedAt().isAfter(now.plusSeconds(5)) || proof.collectionRuleId()==null
                || proof.collectionRuleFingerprint()==null
                || !proof.collectionRuleFingerprint().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("independent finalized collection differs from original transfer or inventory");
        var definition=assets.find(wallet.chain(),row.assetSymbol()).orElse(null);
        boolean nativeAsset=definition!=null && Boolean.TRUE.equals(definition.getNativeAsset());
        if(nativeAsset?proof.tokenIdentifier()!=null
                :tokens.listByChainAndNetwork(wallet.chain(),wallet.network()).stream()
                    .noneMatch(token->token.symbol().equals(row.assetSymbol())
                        && token.contractAddress().equalsIgnoreCase(proof.tokenIdentifier()==null?"":proof.tokenIdentifier())
                        && token.decimals()==proof.decimals()))
            throw new IllegalStateException("independent finalized collection token identity differs from local network");
    }

    private ReconciliationView view(CustodyHotWalletRepository.Wallet wallet,
            CollectionRecordRepository.InboundAccounting row,boolean newlyAccounted,
            HotWalletSettledCollectionObservation proof) {
        return new ReconciliationView(wallet.id(),wallet.chain(),row.assetSymbol(),row.collectionNo(),
                row.txHash(),proof==null?row.receiptBlockHeight():proof.receiptBlockHeight(),
                proof==null?row.receiptBlockHash():proof.receiptBlockHash(),
                proof==null?row.observedBlockHeight():proof.observedBlockHeight(),
                proof==null?row.observedBlockHash():proof.observedBlockHash(),
                collections.inboundExposure(row.tenantId(),row.chain(),row.assetSymbol(),row.toAddress()),newlyAccounted);
    }
}

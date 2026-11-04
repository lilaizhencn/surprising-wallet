package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.HotWalletInventoryObservation;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Caches fresh independent chain evidence without crediting customer accounts. */
@Service
public class HotWalletInventoryService {
    private static final Logger LOG=LoggerFactory.getLogger(HotWalletInventoryService.class);
    private UUID activeCursor;
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletAssetPolicyRepository policies;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final ChainAssetRepository assets;
    private final CustodyAuditRepository audits;
    private final WithdrawalApprovalClient approval;
    private final TransactionTemplate transaction;

    public HotWalletInventoryService(CustodyHotWalletRepository wallets,
            CustodyHotWalletAssetPolicyRepository policies,
            CustodyHotWalletInventoryObservationRepository observations,
            CustodyAddressRepository addresses, ChainAddressRepository chainAddresses,
            ChainAssetRepository assets, CustodyAuditRepository audits,
            WithdrawalApprovalClient approval, PlatformTransactionManager manager) {
        this.wallets=wallets;this.policies=policies;this.observations=observations;
        this.addresses=addresses;this.chainAddresses=chainAddresses;this.assets=assets;
        this.audits=audits;this.approval=approval;this.transaction=new TransactionTemplate(manager);
    }

    public record InventoryView(UUID walletId,String chain,String assetSymbol,String address,
                                BigDecimal observedBalance,BigDecimal nativeBalance,
                                BigDecimal targetBalance,BigDecimal maximumBalance,
                                boolean overMaximum,Instant observedAt,String networkIdentity,
                                UUID collectionRuleId,String collectionRuleFingerprint,
                                Long observedBlockHeight,String observedBlockHash) { }

    public InventoryView refresh(CustodyPrincipal principal,UUID walletId,String assetValue,String sourceIp) {
        if(principal==null || principal.tenantId()==null || !principal.hasScope("assets:read")
                || !"TENANT_ADMIN".equals(principal.role()))
            throw new CustodyForbiddenException("tenant administrator assets:read scope required");
        return refreshOwned(principal.tenantId(),walletId,assetValue,
                principal.actorType().name(),principal.actorId().toString(),sourceIp);
    }

    /** Bounded cursor keeps one stale or compromised wallet from starving other active wallets. */
    public synchronized void refreshActive() {
        var active=wallets.activeAfter(activeCursor,10);
        if(active.isEmpty() && activeCursor!=null) {
            activeCursor=null;
            active=wallets.activeAfter(null,10);
        }
        for(var wallet:active) {
            activeCursor=wallet.id();
            try {
                for(var policy:policies.list(wallet.tenantId(),wallet.id())) {
                    CustodyHotWalletInventoryObservationRepository.Observation previous=null;
                    try {
                        previous=observations.find(wallet.tenantId(),wallet.id(),policy.assetSymbol())
                                .orElse(null);
                        refreshOwned(wallet.tenantId(),wallet.id(),policy.assetSymbol(),
                                "SYSTEM","independent-inventory-poller",null);
                    } catch(RuntimeException failure) {
                        if(previous!=null) {
                            try {
                                if(observations.invalidateIfUnchanged(wallet.tenantId(),wallet.id(),
                                        policy.assetSymbol(),previous.observedAt())==1) {
                                    try {
                                        audits.insert(UUID.randomUUID(),wallet.tenantId(),"SYSTEM",
                                                "independent-inventory-poller","HOT_WALLET.INVENTORY_INVALIDATED",
                                                "HOT_WALLET",wallet.id().toString(),null,
                                                "{\"assetSymbol\":\""+policy.assetSymbol()+"\"}");
                                    } catch(RuntimeException auditFailure) {
                                        LOG.error("Hot wallet inventory invalidated but audit failed: tenant={}, wallet={}, asset={}",
                                                wallet.tenantId(),wallet.id(),policy.assetSymbol(),auditFailure);
                                    }
                                }
                            } catch(RuntimeException invalidationFailure) {
                                LOG.error("Hot wallet inventory could not be invalidated: tenant={}, wallet={}, asset={}",
                                        wallet.tenantId(),wallet.id(),policy.assetSymbol(),invalidationFailure);
                            }
                        }
                        LOG.warn("Active hot wallet inventory refresh failed: tenant={}, wallet={}, asset={}, reason={}",
                                wallet.tenantId(),wallet.id(),policy.assetSymbol(),failure.getMessage());
                    }
                }
            } catch(RuntimeException failure) {
                LOG.warn("Active hot wallet policy scan failed: tenant={}, wallet={}, reason={}",
                        wallet.tenantId(),wallet.id(),failure.getMessage());
            }
        }
    }

    private InventoryView refreshOwned(UUID tenantId,UUID walletId,String assetValue,
            String actorType,String actorId,String sourceIp) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("independent chain inventory observation requires no database transaction");
        String asset=assetValue==null?"":assetValue.trim().toUpperCase(Locale.ROOT);
        if(!asset.matches("[A-Z][A-Z0-9_]{1,31}"))throw new IllegalArgumentException("valid asset is required");
        var wallet=wallets.find(tenantId,walletId).orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if(!java.util.Set.of("STANDBY","ACTIVE").contains(wallet.status()))
            throw new IllegalStateException("hot wallet is not available for inventory observation");
        var policy=policies.list(tenantId,walletId).stream()
                .filter(row->row.assetSymbol().equals(asset)).findFirst()
                .orElseThrow(()->new IllegalStateException("hot wallet asset policy is missing"));
        var definition=assets.findActiveDefinition(wallet.chain(),asset)
                .orElseThrow(()->new IllegalStateException("chain asset is not enabled"));
        var address=addresses.require(tenantId,wallet.custodyAddressId());
        var owner=chainAddresses.findOwned(tenantId,address.chainAddressId()).orElseThrow();
        if(!wallet.chain().equals(address.chain()) || !wallet.network().equals(address.network())
                || !"ACTIVE".equals(address.status()) || !"HOT_WITHDRAW".equals(owner.getWalletRole())
                || !Boolean.TRUE.equals(owner.getEnabled()) || !wallet.mpcKeyId().equals(owner.getMpcKeyId())
                || !address.address().equals(owner.getAddress()))
            throw new IllegalStateException("hot wallet address or key ownership changed");
        HotWalletInventoryObservation observed=approval.hotInventory(tenantId,wallet.chain(),
                wallet.network(),asset,wallet.mpcKeyId());
        boolean sameAddress=ChainType.valueOf(wallet.chain()).isEvm()
                ? address.address().equalsIgnoreCase(observed.address())
                : address.address().equals(observed.address());
        Instant now=Instant.now();
        if(!sameAddress || !wallet.chain().equals(observed.chain()) || !wallet.network().equals(observed.network())
                || !asset.equals(observed.assetSymbol()) || !wallet.mpcKeyId().equals(observed.keyId())
                || definition.getDecimals()==null || definition.getDecimals()!=observed.decimals()
                || observed.decimals()<0 || observed.decimals()>36
                || observed.nativeDecimals()<0 || observed.nativeDecimals()>36
                || observed.availableAtomic()==null || observed.availableAtomic().signum()<0
                || observed.nativeAtomic()==null || observed.nativeAtomic().signum()<0
                || observed.observedAt()==null || observed.observedAt().isBefore(now.minusSeconds(60))
                || observed.observedAt().isAfter(now.plusSeconds(5))
                || observed.networkIdentity()==null || observed.networkIdentity().isBlank()
                || observed.collectionRuleId()==null || observed.collectionRuleFingerprint()==null
                || !observed.collectionRuleFingerprint().matches("[0-9a-f]{64}")
                || (ChainType.valueOf(wallet.chain()).isEvm()
                    ? observed.observedBlockHeight()==null || observed.observedBlockHeight()<0
                        || observed.observedBlockHash()==null
                        || !observed.observedBlockHash().matches("0x[0-9a-f]{64}")
                    : observed.observedBlockHeight()!=null || observed.observedBlockHash()!=null))
            throw new IllegalStateException("independent hot wallet inventory is stale or differs from its owned asset");
        var row=new CustodyHotWalletInventoryObservationRepository.Observation(tenantId,walletId,
                wallet.chain(),asset,wallet.mpcKeyId(),address.address(),observed.networkIdentity(),
                observed.decimals(),observed.nativeDecimals(),observed.availableAtomic(),observed.nativeAtomic(),
                observed.observedAt(),observed.collectionRuleId(),observed.collectionRuleFingerprint(),
                observed.observedBlockHeight(),observed.observedBlockHash());
        transaction.executeWithoutResult(status->{
            var current=wallets.lock(tenantId,walletId).orElseThrow();
            if(!current.mpcKeyId().equals(wallet.mpcKeyId()) || !current.status().equals(wallet.status())
                    || !current.updatedAt().equals(wallet.updatedAt())
                    || !policies.list(tenantId,walletId).contains(policy))
                throw new IllegalStateException("hot wallet or asset policy changed during inventory observation");
            if(observations.record(row)!=1)
                throw new IllegalStateException("older inventory observation cannot replace newer chain evidence");
            if(!"SYSTEM".equals(actorType))audits.insert(UUID.randomUUID(),tenantId,actorType,
                    actorId,"HOT_WALLET.INVENTORY_OBSERVED","HOT_WALLET",
                    walletId.toString(),sourceIp,"{\"assetSymbol\":\""+asset+"\",\"availableAtomic\":\""
                            +observed.availableAtomic()+"\",\"collectionRuleId\":\""+observed.collectionRuleId()+"\"}");
        });
        var balance=new BigDecimal(observed.availableAtomic()).movePointLeft(observed.decimals());
        var nativeBalance=new BigDecimal(observed.nativeAtomic()).movePointLeft(observed.nativeDecimals());
        return new InventoryView(walletId,wallet.chain(),asset,address.address(),balance,nativeBalance,
                policy.targetBalance(),policy.maximumBalance(),balance.compareTo(policy.maximumBalance())>0,
                observed.observedAt(),observed.networkIdentity(),observed.collectionRuleId(),
                observed.collectionRuleFingerprint(),observed.observedBlockHeight(),observed.observedBlockHash());
    }
}

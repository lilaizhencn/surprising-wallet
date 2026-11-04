package com.surprising.wallet.service;

import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Chooses a collection destination under the same locks as its persisted original record. */
@Service
public class HotWalletCollectionRoutingService {
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletAssetPolicyRepository policies;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CollectionRecordRepository collections;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final WalletMpcKeyRepository keys;
    private final CustodyTenantChainTableRepository tenantChains;
    private final HotWalletAggregateExposureService aggregate;

    public HotWalletCollectionRoutingService(CustodyHotWalletRepository wallets,
            CustodyHotWalletAssetPolicyRepository policies,
            CustodyHotWalletInventoryObservationRepository observations,
            CollectionRecordRepository collections, CustodyAddressRepository addresses,
            ChainAddressRepository chainAddresses, WalletMpcKeyRepository keys,
            CustodyTenantChainTableRepository tenantChains,HotWalletAggregateExposureService aggregate) {
        this.wallets=wallets;this.policies=policies;this.observations=observations;
        this.collections=collections;this.addresses=addresses;this.chainAddresses=chainAddresses;
        this.keys=keys;this.tenantChains=tenantChains;this.aggregate=aggregate;
    }

    /** Empty only before any hot route is active. An active route without headroom never falls back. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Throwable.class)
    public Optional<String> destination(UUID tenantId,String chain,String asset,BigDecimal amount) {
        if(tenantId==null || chain==null || asset==null || amount==null || amount.signum()<=0)
            throw new IllegalArgumentException("positive owned collection amount is required");
        if(!tenantChains.lockCollectionRoutingOpen(tenantId,chain))
            throw new IllegalStateException("tenant chain collection routing is closed");
        var registered=wallets.list(tenantId,chain);
        var active=registered.stream().filter(row->"ACTIVE".equals(row.status())).toList();
        if(active.isEmpty()) {
            if(registered.stream().anyMatch(row->"QUARANTINED".equals(row.status())
                    || "RETIRED".equals(row.status())))
                throw new IllegalStateException("old hot wallet cannot fall back to legacy collection target");
            if(tenantChains.withdrawalPaused(tenantId,chain))
                throw new IllegalStateException("paused chain has no active safe collection wallet");
            return Optional.empty();
        }
        Instant now=Instant.now();
        String fallback=null;
        for(var candidate:active) {
            var wallet=wallets.lock(tenantId,candidate.id()).orElseThrow();
            if(!"ACTIVE".equals(wallet.status()) || !chain.equals(wallet.chain()))continue;
            var policy=policies.list(tenantId,wallet.id()).stream()
                    .filter(row->asset.equals(row.assetSymbol())).findFirst().orElse(null);
            var observed=observations.find(tenantId,wallet.id(),asset).orElse(null);
            if(policy==null || observed==null || observed.invalidatedAt()!=null
                    || observed.observedAt().isBefore(now.minusSeconds(60))
                    || observed.observedAt().isAfter(now.plusSeconds(5))
                    || !wallet.mpcKeyId().equals(observed.keyId())
                    || !chain.equals(observed.chain()) || !asset.equals(observed.assetSymbol())
                    || (ChainType.valueOf(chain).isEvm()
                        && (observed.observedBlockHeight()==null || observed.observedBlockHash()==null)))continue;
            try { amount.movePointRight(observed.decimals()).toBigIntegerExact(); }
            catch(ArithmeticException error) { throw new IllegalArgumentException("collection exceeds observed asset precision",error); }
            var address=addresses.require(tenantId,wallet.custodyAddressId());
            var owner=chainAddresses.findOwned(tenantId,address.chainAddressId()).orElseThrow();
            var key=keys.find(tenantId,wallet.mpcKeyId()).orElseThrow();
            if(!wallet.network().equals(address.network()) || !chain.equals(address.chain())
                    || !"ACTIVE".equals(address.status()) || !"HOT_WITHDRAW".equals(owner.getWalletRole())
                    || !Boolean.TRUE.equals(owner.getEnabled()) || !wallet.mpcKeyId().equals(owner.getMpcKeyId())
                    || !address.address().equals(owner.getAddress()) || !address.address().equals(observed.address())
                    || key.state()!=MpcKey.State.ACTIVE)continue;
            BigDecimal observedBalance=new BigDecimal(observed.availableAtomic()).movePointLeft(observed.decimals());
            BigDecimal inFlight=collections.inboundExposure(tenantId,chain,asset,address.address());
            BigDecimal after=observedBalance.add(inFlight).add(amount);
            if(after.compareTo(policy.maximumBalance())>0)continue;
            aggregate.requireHeadroom(tenantId,chain,asset,null,amount);
            if(after.compareTo(policy.targetBalance())<=0)return Optional.of(address.address());
            if(fallback==null)fallback=address.address();
        }
        if(fallback!=null)return Optional.of(fallback);
        throw new IllegalStateException("active hot wallets have no fresh collection inventory or balance headroom");
    }
}

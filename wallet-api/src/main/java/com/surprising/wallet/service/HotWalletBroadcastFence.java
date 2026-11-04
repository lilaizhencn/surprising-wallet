package com.surprising.wallet.service;

import com.surprising.wallet.common.signing.Evm7702CollectionPayload;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Final local stop before submitting or resubmitting a signed payout wire. */
@Service
@RequiredArgsConstructor
public class HotWalletBroadcastFence {
    private final CustodyTenantChainTableRepository chains;
    private final CustodyHotWalletRepository wallets;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final CollectionRecordRepository collections;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final PlatformTransactionManager manager;

    @FunctionalInterface public interface Broadcast<T> { T send() throws Exception; }

    public <T> T withPayoutBroadcastPermit(UUID tenantId,String chain,UUID authorityKeyId,Broadcast<T> broadcast) {
        if(broadcast==null)throw new IllegalArgumentException("payout broadcast is required");
        return new TransactionTemplate(manager).execute(status -> {
            requireAllowed(tenantId,chain,authorityKeyId);
            return send(broadcast);
        });
    }

    /** Stop old UTXO queue wires once that tenant-chain has an emergency pause. */
    public <T> T withLegacyBroadcastPermit(UUID tenantId,String chain,Broadcast<T> broadcast) {
        if(tenantId==null || chain==null || broadcast==null)
            throw new IllegalArgumentException("legacy broadcast owner is required");
        return new TransactionTemplate(manager).execute(status -> {
            if(!chains.lockLegacyBroadcastAllowed(tenantId,chain))
                throw new IllegalStateException("tenant legacy broadcast is emergency paused");
            return send(broadcast);
        });
    }

    /** Native XMR holder RPC must stay outside a Spring transaction, but still serialize with pause. */
    public <T> T withExternalLegacyBroadcastPermit(UUID tenantId,String chain,Broadcast<T> broadcast) {
        if(tenantId==null || chain==null || broadcast==null)
            throw new IllegalArgumentException("native relay owner is required");
        try(var ignored=chains.lockExternalLegacyBroadcast(tenantId,chain)) {
            try{return broadcast.send();}
            catch(RuntimeException error){throw error;}
            catch(Exception error){throw new IllegalStateException("native relay failed",error);}
        }
    }

    public <T> T withEip7702CollectionBroadcastPermit(UUID tenantId,String chain,String network,
            Evm7702CollectionPayload original,Broadcast<T> broadcast) {
        if(broadcast==null)throw new IllegalArgumentException("collection broadcast is required");
        return new TransactionTemplate(manager).execute(status -> {
            requireCollectionAllowed(tenantId,chain,network,original);
            return send(broadcast);
        });
    }

    /** Keep the tenant-chain shared lock until the RPC call returns, so an incident pause cannot commit first. */
    public <T> T withEvmCollectionBroadcastPermit(UUID tenantId,String chain,String network,
            String collectionNo,String signedHash,UUID sourceKeyId,Broadcast<T> broadcast) {
        return withAccountCollectionBroadcastPermit(tenantId,chain,network,collectionNo,
                signedHash,sourceKeyId,broadcast);
    }

    /** Exact original collection and destination for any supported account chain. */
    public <T> T withAccountCollectionBroadcastPermit(UUID tenantId,String chain,String network,
            String collectionNo,String signedHash,UUID sourceKeyId,Broadcast<T> broadcast) {
        if(tenantId==null || chain==null || network==null || collectionNo==null
                || signedHash==null || sourceKeyId==null || broadcast==null)
            throw new IllegalArgumentException("complete original account collection broadcast required");
        return new TransactionTemplate(manager).execute(status -> {
            if(!chains.lockCollectionRoutingOpen(tenantId,chain))
                throw new IllegalStateException("tenant collection broadcast route is closed");
            var original=collections.lockForSubmission(tenantId,chain,collectionNo)
                    .orElseThrow(()->new IllegalStateException("original collection is missing"));
            if(original.getTxHash()==null || !signedHash.equalsIgnoreCase(original.getTxHash())
                    || !java.util.Set.of("SIGNING","BROADCAST_UNKNOWN","SENT","CONFIRMING")
                        .contains(original.getStatus()))
                throw new IllegalStateException("original collection signed hash or state changed");
            wallets.lockByKeyForSigning(tenantId,chain,sourceKeyId).ifPresent(wallet -> {
                if(!"ACTIVE".equals(wallet.status())
                        || !observations.lockAllUninvalidated(tenantId,wallet.id()))
                    throw new IllegalStateException("unsafe hot wallet cannot broadcast a collection");
            });
            if(chains.withdrawalPausedForSigning(tenantId,chain)
                    && !safeDestination(tenantId,chain,network,original.getToAddress()))
                throw new IllegalStateException("paused collection cannot broadcast to an old destination");
            return send(broadcast);
        });
    }

    private static <T> T send(Broadcast<T> broadcast) {
        try { return broadcast.send(); }
        catch(Exception error) { throw new IllegalStateException("signed transaction broadcast failed",error); }
    }

    public void requireAllowed(UUID tenantId, String chain, UUID authorityKeyId) {
        if (tenantId == null || chain == null || authorityKeyId == null
                || chains.withdrawalPausedForSigning(tenantId, chain)) {
            throw new IllegalStateException("tenant payout broadcast is emergency paused");
        }
        wallets.lockByKeyForSigning(tenantId, chain, authorityKeyId).ifPresent(wallet -> {
            if (!"ACTIVE".equals(wallet.status())
                    || !observations.lockAllUninvalidated(tenantId,wallet.id())) {
                throw new IllegalStateException("unsafe original hot wallet cannot broadcast a payout");
            }
        });
    }

    /** A collection relayer cannot submit a signed batch after any original authority is quarantined. */
    public void requireCollectionAllowed(UUID tenantId, String chain, String network,
                                         Evm7702CollectionPayload original) {
        if (tenantId == null || chain == null || network == null || original == null || original.items().isEmpty()
                || !chains.lockCollectionRoutingOpen(tenantId, chain)) {
            throw new IllegalStateException("tenant collection broadcast has no open original route");
        }
        boolean paused=chains.withdrawalPausedForSigning(tenantId,chain);
        for (var item : original.items()) {
            var authority = item.authority();
            if (authority == null || !tenantId.equals(authority.getTenantId())
                    || !chain.equals(authority.getChain()) || authority.getMpcKeyId() == null) {
                throw new IllegalStateException("collection authority differs from original tenant chain");
            }
            wallets.lockByKeyForSigning(tenantId, chain, authority.getMpcKeyId()).ifPresent(wallet -> {
                if (!"ACTIVE".equals(wallet.status())
                        || !observations.lockAllUninvalidated(tenantId,wallet.id())) {
                    throw new IllegalStateException("unsafe original hot wallet cannot broadcast a collection");
                }
            });
            if(paused && (item.request()==null || !safeDestination(tenantId,chain,network,item.request().recipient())))
                throw new IllegalStateException("paused collection cannot broadcast to an old destination");
        }
    }
    private boolean safeDestination(UUID tenantId,String chain,String network,String recipient) {
        for(var wallet:wallets.list(tenantId,chain)) {
            if(!"ACTIVE".equals(wallet.status()) || !network.equals(wallet.network()))continue;
            wallet=wallets.lock(tenantId,wallet.id()).orElse(null);
            if(wallet==null || !"ACTIVE".equals(wallet.status()) || !network.equals(wallet.network()))continue;
            var address=addresses.require(tenantId,wallet.custodyAddressId());
            var owner=chainAddresses.findOwned(tenantId,address.chainAddressId()).orElse(null);
            if(owner!=null && "ACTIVE".equals(address.status()) && chain.equals(address.chain())
                    && network.equals(address.network()) && "HOT_WITHDRAW".equals(owner.getWalletRole())
                    && Boolean.TRUE.equals(owner.getEnabled()) && wallet.mpcKeyId().equals(owner.getMpcKeyId())
                    && address.address().equals(owner.getAddress())
                    && observations.lockAllUninvalidated(tenantId,wallet.id())
                    && (com.surprising.wallet.common.chain.ChainType.valueOf(chain).isEvm()
                            ?address.address().equalsIgnoreCase(recipient):address.address().equals(recipient)))return true;
        }
        return false;
    }
}

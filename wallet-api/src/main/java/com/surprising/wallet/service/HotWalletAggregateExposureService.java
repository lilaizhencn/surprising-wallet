package com.surprising.wallet.service;

import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Enforces one tenant/chain/asset cap across every active withdrawal address. */
@Service
public class HotWalletAggregateExposureService {
    private final CustodyHotWalletExposureLimitRepository limits;
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletAssetPolicyRepository policies;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CustodyAddressRepository addresses;
    private final CollectionRecordRepository collections;
    private final CustodyTenantChainTableRepository chains;
    private final ChainAssetRepository assets;
    private final CustodyAuditRepository audit;

    public HotWalletAggregateExposureService(CustodyHotWalletExposureLimitRepository limits,
            CustodyHotWalletRepository wallets,CustodyHotWalletAssetPolicyRepository policies,
            CustodyHotWalletInventoryObservationRepository observations,CustodyAddressRepository addresses,
            CollectionRecordRepository collections,CustodyTenantChainTableRepository chains,
            ChainAssetRepository assets,CustodyAuditRepository audit) {
        this.limits=limits;this.wallets=wallets;this.policies=policies;this.observations=observations;
        this.addresses=addresses;this.collections=collections;this.chains=chains;this.assets=assets;this.audit=audit;
    }

    public record SetLimitCommand(String maximumActiveBalance) { }

    @Transactional(readOnly=true)
    public List<CustodyHotWalletExposureLimitRepository.Limit> list(CustodyPrincipal principal,String chain) {
        requireScope(principal,"assets:read");
        return limits.list(principal.tenantId(),canonicalChain(chain));
    }

    @Transactional(rollbackFor=Throwable.class)
    public CustodyHotWalletExposureLimitRepository.Limit set(CustodyPrincipal principal,String chainValue,
            String assetValue,SetLimitCommand command,String sourceIp) {
        requireScope(principal,"chains:write");
        if(!"TENANT_ADMIN".equals(principal.role()))throw new CustodyForbiddenException("tenant administrator required");
        String chain=canonicalChain(chainValue),asset=assetValue==null?"":assetValue.trim().toUpperCase(java.util.Locale.ROOT);
        var definition=assets.findActiveDefinition(chain,asset)
                .orElseThrow(()->new IllegalArgumentException("active hot wallet asset required"));
        if(command==null || command.maximumActiveBalance()==null || !command.maximumActiveBalance().matches("(?:0|[1-9][0-9]{0,53})(?:\\.[0-9]{1,24})?"))
            throw new IllegalArgumentException("positive exact aggregate hot wallet limit required");
        BigDecimal amount=new BigDecimal(command.maximumActiveBalance());
        if(amount.signum()<=0 || definition.getDecimals()==null || amount.scale()>definition.getDecimals())
            throw new IllegalArgumentException("aggregate hot wallet limit exceeds asset precision");
        if(!chains.lockForHotWalletActivation(principal.tenantId(),chain))
            throw new IllegalStateException("tenant chain closed before aggregate limit update");
        var value=new CustodyHotWalletExposureLimitRepository.Limit(principal.tenantId(),chain,asset,amount);
        if(limits.upsert(value)!=1)throw new IllegalStateException("aggregate hot wallet limit update failed");
        audit.insert(UUID.randomUUID(),principal.tenantId(),principal.actorType().name(),
                principal.actorId().toString(),"HOT_WALLET.AGGREGATE_LIMIT","CHAIN_ASSET",
                chain+":"+asset,sourceIp,"{\"maximumActiveBalance\":\""+amount.toPlainString()+"\"}");
        return value;
    }

    /** Caller holds the tenant-chain route lock. The limit row serializes additions on different wallets. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Throwable.class)
    public void requireHeadroom(UUID tenant,String chain,String asset,UUID activatingWallet,BigDecimal incoming) {
        var limit=limits.lock(tenant,chain,asset)
                .orElseThrow(()->new IllegalStateException("aggregate hot wallet asset limit is not configured"));
        Instant now=Instant.now();BigDecimal total=BigDecimal.ZERO;
        boolean seenCandidate=activatingWallet==null;
        for(var wallet:wallets.list(tenant,chain)) {
            boolean candidate=wallet.id().equals(activatingWallet);
            if(!"ACTIVE".equals(wallet.status()) && !candidate)continue;
            if(candidate)seenCandidate=true;
            var policy=policies.list(tenant,wallet.id()).stream()
                    .filter(row->asset.equals(row.assetSymbol())).findFirst()
                    .orElseThrow(()->new IllegalStateException("active hot wallet asset policy is missing"));
            var observed=observations.find(tenant,wallet.id(),asset)
                    .orElseThrow(()->new IllegalStateException("active hot wallet inventory is missing"));
            var address=addresses.require(tenant,wallet.custodyAddressId());
            if(observed.invalidatedAt()!=null || observed.observedAt().isBefore(now.minusSeconds(60))
                    || observed.observedAt().isAfter(now.plusSeconds(5))
                    || !wallet.mpcKeyId().equals(observed.keyId())
                    || !wallet.chain().equals(observed.chain()) || !wallet.network().equals(address.network())
                    || !asset.equals(observed.assetSymbol()) || !address.address().equals(observed.address())
                    || (com.surprising.wallet.common.chain.ChainType.valueOf(chain).isEvm()
                        && (observed.observedBlockHeight()==null || observed.observedBlockHash()==null)))
                throw new IllegalStateException("active hot wallet aggregate inventory is stale or invalid");
            BigDecimal balance=new BigDecimal(observed.availableAtomic()).movePointLeft(observed.decimals());
            if(balance.compareTo(policy.maximumBalance())>0)
                throw new IllegalStateException("active hot wallet exceeds its individual maximum balance");
            total=total.add(balance).add(collections.inboundExposure(tenant,chain,asset,address.address()));
        }
        if(!seenCandidate)throw new IllegalStateException("aggregate activation candidate disappeared");
        if(total.add(incoming).compareTo(limit.maximumActiveBalance())>0)
            throw new IllegalStateException("aggregate active hot wallet balance limit exceeded");
    }

    private static String canonicalChain(String value) {
        if(value==null)throw new IllegalArgumentException("chain is required");
        return com.surprising.wallet.common.chain.ChainType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)).name();
    }
    private static void requireScope(CustodyPrincipal principal,String scope) {
        if(principal==null || principal.tenantId()==null || !principal.hasScope(scope))
            throw new CustodyForbiddenException(scope+" scope required");
    }
}

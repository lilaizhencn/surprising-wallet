package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.common.approval.ApprovalKeyQuarantine;
import com.surprising.wallet.common.approval.CollectionDestinationPolicy;
import com.surprising.wallet.common.approval.HotWalletIncidentInventoryObservation;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.signing.MpcNodes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independently derived standby withdrawal addresses and exact per-asset inventory limits. */
@Service
public class CustodyHotWalletService {
    private static final Logger LOG = LoggerFactory.getLogger(CustodyHotWalletService.class);
    private static final long HOT_CHILD_INDEX = 2L;
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletAssetPolicyRepository policies;
    private final CustodyAddressService addressService;
    private final CustodyAddressRepository custodyAddresses;
    private final ChainAddressRepository chainAddresses;
    private final CustodyTenantChainService chains;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokenConfigs;
    private final CustodyAuditRepository audits;
    private final WithdrawalApprovalClient approval;
    private final MpcNodes nodes;
    private final HotWalletInventoryService inventory;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CustodyTenantChainTableRepository chainRows;
    private final WalletMpcKeyRepository keys;
    private final HotWalletAggregateExposureService aggregate;
    private final TransactionTemplate transaction;

    public CustodyHotWalletService(CustodyHotWalletRepository wallets,
            CustodyHotWalletAssetPolicyRepository policies, CustodyAddressService addressService,
            CustodyAddressRepository custodyAddresses, ChainAddressRepository chainAddresses,
            CustodyTenantChainService chains,
            ChainAssetRepository assets, TokenConfigRepository tokenConfigs,
            CustodyAuditRepository audits, WithdrawalApprovalClient approval,
            MpcNodes nodes,
            HotWalletInventoryService inventory,
            CustodyHotWalletInventoryObservationRepository observations,
            CustodyTenantChainTableRepository chainRows, WalletMpcKeyRepository keys,
            HotWalletAggregateExposureService aggregate,
            PlatformTransactionManager manager) {
        this.wallets = wallets;
        this.policies = policies;
        this.addressService = addressService;
        this.custodyAddresses = custodyAddresses;
        this.chainAddresses = chainAddresses;
        this.chains = chains;
        this.assets = assets;
        this.tokenConfigs = tokenConfigs;
        this.audits = audits;
        this.approval = approval;
        this.nodes = nodes;
        this.inventory=inventory;this.observations=observations;this.chainRows=chainRows;this.keys=keys;this.aggregate=aggregate;
        this.transaction = new TransactionTemplate(manager);
    }

    public record CreateCommand(String chain, String label) { }
    public record PolicyCommand(String targetBalance, String maximumBalance,
                                String maximumSingleWithdrawal, String maximum24hWithdrawal) { }
    public record ActivateCommand(int routePriority) { }
    public record EmergencySwitchCommand(UUID oldWalletId, UUID standbyWalletId,
                                         int routePriority, String reason) { }
    public record EmergencySwitchView(WalletView quarantinedWallet, WalletView activatedWallet,
                                      boolean withdrawalPaused, boolean assetTransferComplete) { }
    public record IncidentInventoryAsset(String assetSymbol, String tokenIdentifier, BigDecimal observedBalance,
            BigDecimal observedNativeBalance, Instant observedAt, String networkIdentity,
            String accountCodeState, String delegatedTo, String observedBlockHash) { }
    public record IncidentInventoryView(UUID walletId, String chain, String address,
            List<IncidentInventoryAsset> assets, String rescueReadiness,
            boolean assetTransferComplete) { }
    public record WalletView(UUID id, UUID custodyAddressId, String chain, String network,
                             String address, String label, String status, int routePriority,
                             boolean quarantineFenceConfirmed,
                             List<CustodyHotWalletAssetPolicyRepository.Policy> policies) { }
    public record CollectionRulePreflight(UUID walletId, String address,
                                          List<CollectionDestinationPolicy> independentPolicies) { }

    @Transactional(readOnly = true)
    public List<WalletView> list(CustodyPrincipal principal, String chainValue) {
        requireScope(principal, "assets:read");
        String chain = normalizeChain(chainValue);
        return wallets.list(principal.tenantId(), chain).stream()
                .map(row -> toView(principal.tenantId(), row)).toList();
    }

    public WalletView createStandby(CustodyPrincipal principal, CreateCommand command, String sourceIp) {
        requireTenantAdmin(principal, "addresses:write");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("hot wallet address creation requires a committed caller transaction");
        }
        String chain = normalizeChain(command.chain());
        String label = command.label() == null ? "" : command.label().trim();
        if (label.isEmpty() || label.length() > 160) {
            throw new IllegalArgumentException("hot wallet label must be 1 to 160 characters");
        }
        chains.requireActive(principal.tenantId(), chain);
        UUID id = UUID.randomUUID();
        var prepared = addressService.prepareSystemAtChildIndex(principal,
                new CustodyAddressService.CreateAddressCommand(chain,
                        "__sw_hot__:" + chain + ":" + id, null, label,
                        Map.of("systemPurpose", "HOT_WITHDRAWAL_STANDBY")),
                HOT_CHILD_INDEX, sourceIp);
        return transaction.execute(status -> {
            var address = addressService.publishPrepared(prepared);
            if (!chain.equals(address.chain()) || !"ACTIVE".equals(address.status())) {
                throw new IllegalStateException("prepared standby address changed before registration");
            }
            var custody = custodyAddresses.require(principal.tenantId(), address.id());
            var owner = chainAddresses.findOwned(principal.tenantId(), custody.chainAddressId())
                    .orElseThrow(() -> new IllegalStateException("standby address has no tenant-owned chain identity"));
            UUID keyId = owner.getMpcKeyId();
            if (keyId == null || !Boolean.TRUE.equals(owner.getEnabled())
                    || !chain.equals(owner.getChain()) || !address.address().equals(owner.getAddress())) {
                throw new IllegalStateException("standby address has no active owned MPC key");
            }
            if (wallets.insertStandby(id, principal.tenantId(), address.id(), keyId, chain,
                    address.network(), label, principal.actorId()) != 1) {
                throw new IllegalStateException("standby hot wallet registration failed");
            }
            audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(),
                    principal.actorId().toString(), "HOT_WALLET.STANDBY_CREATE", "HOT_WALLET",
                    id.toString(), sourceIp, "{\"chain\":\"" + chain + "\"}");
            return toView(principal.tenantId(), wallets.find(principal.tenantId(), id).orElseThrow());
        });
    }

    /** Checks every enabled asset against a host-installed destination rule; it moves no funds. */
    public CollectionRulePreflight collectionRulePreflight(CustodyPrincipal principal, UUID walletId) {
        requireTenantAdmin(principal, "chains:read");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("independent collection rule lookup requires a committed caller transaction");
        }
        var wallet = wallets.find(principal.tenantId(), walletId)
                .orElseThrow(() -> new IllegalArgumentException("hot wallet not found"));
        if (!"STANDBY".equals(wallet.status())) {
            throw new IllegalStateException("only a standby hot wallet can be checked before activation");
        }
        var address = custodyAddresses.require(principal.tenantId(), wallet.custodyAddressId());
        var owner = chainAddresses.findOwned(principal.tenantId(), address.chainAddressId())
                .orElseThrow(() -> new IllegalStateException("hot wallet chain identity is missing"));
        if (!wallet.chain().equals(address.chain()) || !wallet.network().equals(address.network())
                || !address.address().equals(owner.getAddress()) || !"ACTIVE".equals(address.status())
                || !"HOT_WITHDRAW".equals(owner.getWalletRole()) || !Boolean.TRUE.equals(owner.getEnabled())
                || !wallet.mpcKeyId().equals(owner.getMpcKeyId())) {
            throw new IllegalStateException("standby hot wallet address or key identity changed");
        }
        var configured = policies.list(principal.tenantId(), walletId);
        var enabledAssets = assets.listActiveByChain(wallet.chain()).stream()
                .map(row -> row.getSymbol().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (enabledAssets.isEmpty() || configured.size() != enabledAssets.size()
                || !configured.stream().map(CustodyHotWalletAssetPolicyRepository.Policy::assetSymbol)
                        .collect(java.util.stream.Collectors.toSet()).equals(enabledAssets)) {
            throw new IllegalStateException("every enabled chain asset needs an explicit hot wallet policy");
        }
        var independent = configured.stream().map(policy -> {
            var receipt = approval.collectionDestination(principal.tenantId(), wallet.chain(), wallet.network(),
                    policy.assetSymbol(), wallet.mpcKeyId());
            if (receipt == null) {
                throw new IllegalStateException("independent collection destination is missing");
            }
            boolean sameAddress = ChainType.valueOf(wallet.chain()).isEvm()
                    ? address.address().equalsIgnoreCase(receipt.destinationAddress())
                    : address.address().equals(receipt.destinationAddress());
            if (!sameAddress || !principal.tenantId().equals(receipt.tenantId())
                    || !wallet.chain().equals(receipt.chain()) || !wallet.network().equals(receipt.network())
                    || !policy.assetSymbol().equals(receipt.assetSymbol())
                    || !wallet.mpcKeyId().equals(receipt.destinationKeyId())
                    || receipt.ruleId() == null || receipt.ruleFingerprint() == null
                    || !receipt.ruleFingerprint().matches("[0-9a-f]{64}") || receipt.maximumAmount() == null
                    || receipt.maximumAmount().signum() <= 0 || receipt.maximum24hAmount() == null
                    || receipt.maximum24hAmount().compareTo(receipt.maximumAmount()) < 0) {
                throw new IllegalStateException("independent collection destination differs from the standby wallet");
            }
            return receipt;
        }).toList();
        var latest = wallets.find(principal.tenantId(), walletId).orElseThrow();
        if (!"STANDBY".equals(latest.status()) || !latest.updatedAt().equals(wallet.updatedAt())
                || !policies.list(principal.tenantId(), walletId).equals(configured)) {
            throw new IllegalStateException("standby hot wallet or policy changed during independent policy lookup");
        }
        return new CollectionRulePreflight(walletId, address.address(), independent);
    }

    /** Reads independent rules and actual inventory before a short atomic route transition. */
    public WalletView activate(CustodyPrincipal principal, UUID walletId, ActivateCommand command, String sourceIp) {
        requireTenantAdmin(principal,"chains:write");
        requireScope(principal,"chains:read");
        requireScope(principal,"assets:read");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("hot wallet activation requires committed independent checks");
        if(command==null || command.routePriority()<0 || command.routePriority()>1_000_000)
            throw new IllegalArgumentException("hot wallet route priority must be 0 to 1000000");
        var original=wallets.find(principal.tenantId(),walletId)
                .orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if(!"STANDBY".equals(original.status()))
            throw new IllegalStateException("only a standby hot wallet may be activated");
        var chainType=ChainType.valueOf(original.chain());
        if(chainType.isUtxo() || chainType==ChainType.XMR)
            throw new IllegalStateException("this chain requires native inventory and payout routing before activation");
        var configured=policies.list(principal.tenantId(),walletId);
        var preflight=collectionRulePreflight(principal,walletId);
        for(var policy:configured)inventory.refresh(principal,walletId,policy.assetSymbol(),sourceIp);
        return transaction.execute(status->{
            if(!chainRows.lockForHotWalletActivation(principal.tenantId(),original.chain()))
                throw new IllegalStateException("tenant chain closed before hot wallet activation");
            var current=wallets.lock(principal.tenantId(),walletId).orElseThrow();
            if(!"STANDBY".equals(current.status()) || !current.updatedAt().equals(original.updatedAt())
                    || !current.mpcKeyId().equals(original.mpcKeyId())
                    || !policies.list(principal.tenantId(),walletId).equals(configured))
                throw new IllegalStateException("standby wallet or policy changed before route activation");
            var enabled=assets.listActiveByChain(current.chain()).stream()
                    .map(row->row.getSymbol().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            var covered=configured.stream().map(CustodyHotWalletAssetPolicyRepository.Policy::assetSymbol)
                    .collect(java.util.stream.Collectors.toSet());
            if(enabled.isEmpty() || !enabled.equals(covered) || configured.size()!=covered.size()
                    || preflight.independentPolicies().size()!=configured.size())
                throw new IllegalStateException("active chain assets lack exact independent destination policies");
            var key=keys.find(principal.tenantId(),current.mpcKeyId()).orElseThrow();
            if(key.state()!=MpcKey.State.ACTIVE)
                throw new IllegalStateException("hot wallet MPC key is not active");
            var address=custodyAddresses.require(principal.tenantId(),current.custodyAddressId());
            var owner=chainAddresses.findOwned(principal.tenantId(),address.chainAddressId()).orElseThrow();
            if(!"ACTIVE".equals(address.status()) || !"HOT_WITHDRAW".equals(owner.getWalletRole())
                    || !Boolean.TRUE.equals(owner.getEnabled()) || !current.mpcKeyId().equals(owner.getMpcKeyId())
                    || !address.address().equals(owner.getAddress()) || !preflight.address().equals(address.address()))
                throw new IllegalStateException("hot wallet owned address changed before activation");
            Instant now=Instant.now();
            for(var policy:configured) {
                var rule=preflight.independentPolicies().stream()
                        .filter(row->policy.assetSymbol().equals(row.assetSymbol())).findFirst().orElseThrow();
                var observed=observations.find(principal.tenantId(),walletId,policy.assetSymbol()).orElseThrow();
                BigDecimal balance=new BigDecimal(observed.availableAtomic()).movePointLeft(observed.decimals());
                if(observed.invalidatedAt()!=null || observed.observedAt().isBefore(now.minusSeconds(60))
                        || observed.observedAt().isAfter(now.plusSeconds(5))
                        || !current.mpcKeyId().equals(observed.keyId()) || !address.address().equals(observed.address())
                        || (ChainType.valueOf(current.chain()).isEvm()
                            && (observed.observedBlockHeight()==null || observed.observedBlockHash()==null))
                        || !rule.ruleId().equals(observed.collectionRuleId())
                        || !rule.ruleFingerprint().equals(observed.collectionRuleFingerprint())
                        || balance.compareTo(policy.maximumBalance())>0)
                    throw new IllegalStateException("hot wallet inventory or independent rule changed before activation");
                aggregate.requireHeadroom(principal.tenantId(),current.chain(),policy.assetSymbol(),walletId,BigDecimal.ZERO);
            }
            if(wallets.activate(principal.tenantId(),walletId,current.mpcKeyId(),command.routePriority())!=1)
                throw new IllegalStateException("hot wallet activation raced");
            audits.insert(UUID.randomUUID(),principal.tenantId(),principal.actorType().name(),
                    principal.actorId().toString(),"HOT_WALLET.ACTIVATE","HOT_WALLET",walletId.toString(),
                    sourceIp,"{\"chain\":\""+current.chain()+"\",\"routePriority\":"+command.routePriority()+"}");
            return toView(principal.tenantId(),wallets.find(principal.tenantId(),walletId).orElseThrow());
        });
    }

    /** Durable staged incident response. A failure leaves the chain paused and never implies a sweep. */
    public EmergencySwitchView emergencySwitch(CustodyPrincipal principal, EmergencySwitchCommand command,
                                               String sourceIp) {
        requireTenantAdmin(principal,"chains:write");
        requireScope(principal,"chains:read");
        requireScope(principal,"assets:read");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("emergency switch requires independently committed stages");
        if(command==null || command.oldWalletId()==null || command.standbyWalletId()==null
                || command.oldWalletId().equals(command.standbyWalletId()))
            throw new IllegalArgumentException("distinct old and standby hot wallets are required");
        String explanation=command.reason()==null?"":command.reason().trim();
        if(explanation.length()<8 || explanation.length()>500)
            throw new IllegalArgumentException("emergency switch reason must be 8 to 500 characters");
        var old=wallets.find(principal.tenantId(),command.oldWalletId())
                .orElseThrow(()->new IllegalArgumentException("old hot wallet not found"));
        var next=wallets.find(principal.tenantId(),command.standbyWalletId())
                .orElseThrow(()->new IllegalArgumentException("standby hot wallet not found"));
        if(!old.chain().equals(next.chain()) || !old.network().equals(next.network())
                || !java.util.Set.of("ACTIVE","QUARANTINED").contains(old.status())
                || !java.util.Set.of("STANDBY","ACTIVE").contains(next.status()))
            throw new IllegalStateException("emergency wallets must share a chain and be active/standby");
        var oldAddress=custodyAddresses.require(principal.tenantId(),old.custodyAddressId());
        var nextAddress=custodyAddresses.require(principal.tenantId(),next.custodyAddressId());
        if(old.mpcKeyId().equals(next.mpcKeyId())
                || (ChainType.valueOf(old.chain()).isEvm()
                    ?oldAddress.address().equalsIgnoreCase(nextAddress.address())
                    :oldAddress.address().equals(nextAddress.address())))
            throw new IllegalStateException("emergency replacement must use a distinct key and chain address");
        transaction.executeWithoutResult(status -> {
            chains.setWithdrawalPaused(principal,old.chain(),true,explanation,sourceIp);
            var locallyQuarantined=quarantineLocal(principal,old.id(),explanation,sourceIp);
            if(!old.chain().equals(locallyQuarantined.chain()) || !old.network().equals(locallyQuarantined.network())
                    || !old.mpcKeyId().equals(locallyQuarantined.mpcKeyId()))
                throw new IllegalStateException("emergency old hot wallet identity changed before local quarantine");
        });
        var quarantined=quarantine(principal,old.id(),explanation,sourceIp);
        if(!quarantined.quarantineFenceConfirmed())
            throw new IllegalStateException("old hot wallet native and independent quarantine is pending");
        var activated="ACTIVE".equals(next.status())
                ? toView(principal.tenantId(),wallets.find(principal.tenantId(),next.id()).orElseThrow())
                : activate(principal,next.id(),new ActivateCommand(command.routePriority()),sourceIp);
        if(!"ACTIVE".equals(activated.status()) || !chainRows.withdrawalPaused(principal.tenantId(),old.chain()))
            throw new IllegalStateException("emergency switch did not retain withdrawal pause");
        return new EmergencySwitchView(quarantined,activated,true,false);
    }

    /** Quarantined keys remain observable, but this path never grants signing or customer credit. */
    public IncidentInventoryView incidentInventory(CustodyPrincipal principal,UUID walletId,String sourceIp) {
        requireTenantAdmin(principal,"assets:read");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("independent incident inventory requires a committed caller transaction");
        var wallet=wallets.find(principal.tenantId(),walletId)
                .orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if(!"QUARANTINED".equals(wallet.status()) || wallet.quarantineFenceConfirmedAt()==null)
            throw new IllegalStateException("incident inventory requires confirmed key quarantine");
        var address=custodyAddresses.require(principal.tenantId(),wallet.custodyAddressId());
        var owner=chainAddresses.findOwned(principal.tenantId(),address.chainAddressId()).orElseThrow();
        if(!wallet.chain().equals(address.chain()) || !wallet.network().equals(address.network())
                || !wallet.mpcKeyId().equals(owner.getMpcKeyId()) || !"HOT_WITHDRAW".equals(owner.getWalletRole())
                || !address.address().equals(owner.getAddress()))
            throw new IllegalStateException("incident hot wallet original address identity changed");
        var event=new ApprovalKeyQuarantine(wallet.tenantId(),wallet.mpcKeyId(),
                wallet.quarantineActorId(),wallet.quarantineAt(),wallet.quarantineReason());
        var definitions=assets.listDefinitionsByChain(wallet.chain());
        var bySymbol=definitions.stream().collect(java.util.stream.Collectors.toMap(
                row->row.getSymbol().toUpperCase(Locale.ROOT),row->row));
        var nativeAssets=definitions.stream().filter(row->Boolean.TRUE.equals(row.getNativeAsset())).toList();
        if(nativeAssets.size()!=1 || nativeAssets.getFirst().getDecimals()==null)
            throw new IllegalStateException("incident native asset definition is missing or ambiguous");
        int nativeDecimals=nativeAssets.getFirst().getDecimals();
        var evm=com.surprising.wallet.common.chain.ChainType.valueOf(wallet.chain()).isEvm();
        var evmTokens=evm?tokenConfigs.listByChainAndNetwork(wallet.chain(),wallet.network()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row->row.symbol().toUpperCase(Locale.ROOT),row->row)):java.util.Map.<String,com.surprising.wallet.chain.model.TokenConfiguration>of();
        String nativeSymbol=nativeAssets.getFirst().getSymbol().toUpperCase(Locale.ROOT);
        if(evmTokens.containsKey(nativeSymbol))
            throw new IllegalStateException("incident native and token symbols are ambiguous");
        var symbols=new java.util.TreeSet<String>();
        for(var policy:policies.list(principal.tenantId(),walletId))symbols.add(policy.assetSymbol());
        // The token row survives a disabled or removed routing projection; another network is excluded.
        if(evm) {
            symbols.add(nativeSymbol);
            symbols.addAll(evmTokens.keySet());
        } else for(var asset:definitions) symbols.add(asset.getSymbol());
        if(symbols.isEmpty())throw new IllegalStateException("incident chain has no configured assets to observe");
        var observed=new java.util.ArrayList<IncidentInventoryAsset>();
        String observedNetworkIdentity=null;
        for(String symbol:symbols) {
            var definition=bySymbol.get(symbol);
            var configured=evmTokens.get(symbol);
            boolean nativeAsset=nativeSymbol.equals(symbol);
            Integer expectedDecimals=nativeAsset || !evm
                    ? (definition==null ? null : definition.getDecimals())
                    : (configured==null ? null : configured.decimals());
            if(expectedDecimals==null)
                throw new IllegalStateException("incident asset definition is missing");
            HotWalletIncidentInventoryObservation result=approval.incidentInventory(principal.tenantId(),
                    wallet.chain(),wallet.network(),symbol,wallet.mpcKeyId());
            if(evm) {
                if(nativeAsset) {
                    if(result==null || result.tokenIdentifier()!=null)
                        throw new IllegalStateException("incident native asset identifier differs");
                } else if(configured==null || configured.contractAddress()==null || result==null
                        || result.tokenIdentifier()==null
                        || !configured.contractAddress().equalsIgnoreCase(result.tokenIdentifier())) {
                    throw new IllegalStateException("incident token contract or precision differs from tenant network");
                }
            }
            if(result==null || !principal.tenantId().equals(result.tenantId())
                    || !wallet.mpcKeyId().equals(result.keyId())
                    || !wallet.chain().equals(result.chain()) || !wallet.network().equals(result.network())
                    || !symbol.equals(result.assetSymbol()) || !"DISABLED".equals(result.keyState())
                    || result.decimals()!=expectedDecimals || result.nativeDecimals()!=nativeDecimals
                    || result.availableAtomic()==null || result.availableAtomic().signum()<0
                    || result.nativeAtomic()==null || result.nativeAtomic().signum()<0
                    || result.observedAt()==null || result.networkIdentity()==null
                    || result.networkIdentity().isBlank())
                throw new IllegalStateException("independent incident inventory identity or precision differs");
            if(observedNetworkIdentity!=null && !observedNetworkIdentity.equals(result.networkIdentity()))
                throw new IllegalStateException("incident assets disagree on network identity");
            observedNetworkIdentity=result.networkIdentity();
            Instant now=Instant.now();
            boolean same=com.surprising.wallet.common.chain.ChainType.valueOf(wallet.chain()).isEvm()
                    ?address.address().equalsIgnoreCase(result.address())
                    :address.address().equals(result.address());
            boolean validEvmEvidence=!com.surprising.wallet.common.chain.ChainType.valueOf(wallet.chain()).isEvm()
                    || (result.accountCodeState()!=null
                        && java.util.Set.of("EMPTY","EIP7702_DELEGATED","UNRECOGNIZED_CODE")
                            .contains(result.accountCodeState())
                        && result.observedBlockHash()!=null
                        && result.observedBlockHash().matches("0x[0-9a-f]{64}")
                        && ("EIP7702_DELEGATED".equals(result.accountCodeState())
                            ? result.delegatedTo()!=null && result.delegatedTo().matches("0x[0-9a-f]{40}")
                            : result.delegatedTo()==null));
            if(!same || !event.hash().equals(result.quarantineHash())
                    || !validEvmEvidence
                    || result.decimals()<0 || result.decimals()>36 || result.nativeDecimals()<0
                    || result.nativeDecimals()>36 || result.observedAt().isBefore(now.minusSeconds(60))
                    || result.observedAt().isAfter(now.plusSeconds(5)))
                throw new IllegalStateException("independent incident balance is stale or differs from quarantined identity");
            observed.add(new IncidentInventoryAsset(symbol,result.tokenIdentifier(),
                    new BigDecimal(result.availableAtomic()).movePointLeft(result.decimals()),
                    new BigDecimal(result.nativeAtomic()).movePointLeft(result.nativeDecimals()),
                    result.observedAt(),result.networkIdentity(),result.accountCodeState(),
                    result.delegatedTo(),result.observedBlockHash()));
        }
        audits.insert(UUID.randomUUID(),principal.tenantId(),principal.actorType().name(),
                principal.actorId().toString(),"HOT_WALLET.INCIDENT_INVENTORY","HOT_WALLET",
                walletId.toString(),sourceIp,"{\"chain\":\""+wallet.chain()+"\",\"assetCount\":"+observed.size()+"}");
        // The payout delegate requires the original EOA signature and no independently
        // installed recovery authority exists for these wallets. Quarantine permanently
        // disables our MPC key, so inventory must never imply an executable sweep.
        return new IncidentInventoryView(walletId,wallet.chain(),address.address(),
                List.copyOf(observed),"NO_INDEPENDENT_RECOVERY_AUTHORITY",false);
    }

    public WalletView quarantine(CustodyPrincipal principal, UUID walletId, String reason, String sourceIp) {
        requireTenantAdmin(principal, "chains:write");
        String explanation = reason == null ? "" : reason.trim();
        if (explanation.length() < 8 || explanation.length() > 500) {
            throw new IllegalArgumentException("hot wallet quarantine reason must be 8 to 500 characters");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("independent key quarantine requires a committed caller transaction");
        }
        var original=wallets.find(principal.tenantId(),walletId)
                .orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        var wallet = transaction.execute(status -> {
            chains.setWithdrawalPaused(principal,original.chain(),true,explanation,sourceIp);
            return quarantineLocal(principal,walletId,explanation,sourceIp);
        });
        if (wallet.quarantineFenceConfirmedAt() != null) return toView(principal.tenantId(), wallet);
        confirmIndependentQuarantine(wallet);
        return toView(principal.tenantId(), wallets.find(principal.tenantId(), walletId).orElseThrow());
    }

    /** Caller transaction also closes the old collection route before this status becomes visible. */
    private CustodyHotWalletRepository.Wallet quarantineLocal(CustodyPrincipal principal,UUID walletId,
                                                               String explanation,String sourceIp) {
        var current=wallets.lock(principal.tenantId(),walletId)
                .orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if("QUARANTINED".equals(current.status()))return current;
        if(wallets.quarantine(principal.tenantId(),walletId,principal.actorId(),Instant.now(),explanation)!=1)
            throw new IllegalStateException("hot wallet cannot be quarantined");
        audits.insert(UUID.randomUUID(),principal.tenantId(),principal.actorType().name(),
                principal.actorId().toString(),"HOT_WALLET.QUARANTINE","HOT_WALLET",
                walletId.toString(),sourceIp,"{\"chain\":\""+current.chain()+"\",\"reason\":"
                        +tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(explanation)+"}");
        return wallets.find(principal.tenantId(),walletId).orElseThrow();
    }

    public void reconcilePendingQuarantines() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        for (var wallet : wallets.pendingApprovalQuarantine(100)) {
            if (System.nanoTime() >= deadline) return;
            try {
                confirmIndependentQuarantine(wallet);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                LOG.warn("Independent hot wallet key quarantine pending: tenant={}, wallet={}",
                        wallet.tenantId(), wallet.id());
            }
        }
    }

    private void confirmIndependentQuarantine(CustodyHotWalletRepository.Wallet wallet) {
        var event = new ApprovalKeyQuarantine(wallet.tenantId(), wallet.mpcKeyId(),
                wallet.quarantineActorId(), wallet.quarantineAt(), wallet.quarantineReason());
        RuntimeException holderFailure = null;
        for (var holder : nodes.clients()) {
            try {
                holder.quarantineKey(event);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                if (holderFailure == null) holderFailure = failure;
                LOG.warn("Native hot wallet key quarantine pending: tenant={}, wallet={}, holder={}",
                        wallet.tenantId(), wallet.id(), holder.node().partyId());
            }
        }
        if (nodes.clients().size() != 3 || holderFailure != null) {
            throw new IllegalStateException("native hot wallet key quarantine is not confirmed on all three holders", holderFailure);
        }
        approval.quarantineKey(event);
        transaction.executeWithoutResult(status -> {
            var current = wallets.lock(wallet.tenantId(), wallet.id()).orElseThrow();
            if (current.quarantineFenceConfirmedAt() == null) {
                if (wallets.confirmQuarantineFence(wallet.tenantId(), wallet.id(),
                        event.actorId(), event.quarantinedAt()) != 1) {
                    throw new IllegalStateException("hot wallet quarantine confirmation raced");
                }
                audits.insert(UUID.randomUUID(), wallet.tenantId(), "SYSTEM", "security-fence",
                        "HOT_WALLET.FENCE_CONFIRMED", "HOT_WALLET", wallet.id().toString(),
                        null, "{\"descriptorHash\":\"" + event.hash() + "\"}");
            }
        });
    }

    @Transactional(rollbackFor = Throwable.class)
    public WalletView setPolicy(CustodyPrincipal principal, UUID walletId, String assetValue,
                                PolicyCommand command, String sourceIp) {
        requireTenantAdmin(principal, "chains:write");
        var wallet = wallets.lock(principal.tenantId(), walletId)
                .orElseThrow(() -> new IllegalArgumentException("hot wallet not found"));
        if (!"STANDBY".equals(wallet.status())) {
            throw new IllegalStateException("only standby hot wallet policy may be edited");
        }
        String asset = normalizeAsset(assetValue);
        var definition = assets.findActiveDefinition(wallet.chain(), asset)
                .orElseThrow(() -> new IllegalArgumentException("asset is unavailable on hot wallet chain"));
        int decimals = definition.getDecimals();
        if (decimals < 0 || decimals > 24) throw new IllegalStateException("unsupported wallet asset precision");
        BigDecimal target = amount(command.targetBalance(), decimals, true);
        BigDecimal maximum = amount(command.maximumBalance(), decimals, false);
        BigDecimal single = amount(command.maximumSingleWithdrawal(), decimals, false);
        BigDecimal day = amount(command.maximum24hWithdrawal(), decimals, false);
        if (target.compareTo(maximum) > 0 || day.compareTo(single) < 0) {
            throw new IllegalArgumentException("hot wallet limits are inconsistent");
        }
        policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(principal.tenantId(), walletId,
                wallet.chain(), asset, target, maximum, single, day));
        audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(),
                principal.actorId().toString(), "HOT_WALLET.POLICY_SET", "HOT_WALLET", walletId.toString(),
                sourceIp, "{\"assetSymbol\":\"" + asset + "\"}");
        return toView(principal.tenantId(), wallet);
    }

    private WalletView toView(UUID tenantId, CustodyHotWalletRepository.Wallet wallet) {
        var address = custodyAddresses.require(tenantId, wallet.custodyAddressId());
        if (!wallet.chain().equals(address.chain()) || !wallet.network().equals(address.network())) {
            throw new IllegalStateException("hot wallet address network differs from its registration");
        }
        return new WalletView(wallet.id(), wallet.custodyAddressId(), wallet.chain(), wallet.network(),
                address.address(), wallet.label(), wallet.status(), wallet.routePriority(),
                wallet.quarantineFenceConfirmedAt() != null,
                policies.list(tenantId, wallet.id()));
    }

    private static BigDecimal amount(String value, int decimals, boolean zeroAllowed) {
        try {
            BigDecimal amount = new BigDecimal(value == null ? "" : value.trim());
            if (amount.scale() > decimals || amount.scale() > 24 || amount.precision() > 78
                    || (zeroAllowed ? amount.signum() < 0 : amount.signum() <= 0)) {
                throw new NumberFormatException("out of range");
            }
            return amount.stripTrailingZeros();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid hot wallet asset amount", error);
        }
    }

    private static String normalizeChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("[A-Z][A-Z0-9_]{1,31}")) throw new IllegalArgumentException("valid chain is required");
        return chain;
    }

    private static String normalizeAsset(String value) {
        String asset = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!asset.matches("[A-Z][A-Z0-9_]{1,31}")) throw new IllegalArgumentException("valid asset is required");
        return asset;
    }

    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }

    private static void requireTenantAdmin(CustodyPrincipal principal, String scope) {
        requireScope(principal, scope);
        if (!"TENANT_ADMIN".equals(principal.role())) throw new CustodyForbiddenException("tenant administrator required");
    }
}

package com.surprising.wallet.service;

import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reserves a new order against a fresh, tenant-owned active hot address. */
@Service
public class HotWalletWithdrawalRoutingService {
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletAssetPolicyRepository policies;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final CustodyHotWalletWithdrawalReservationRepository reservations;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final WalletMpcKeyRepository keys;
    private final CustodyTenantChainTableRepository tenantChains;

    public HotWalletWithdrawalRoutingService(CustodyHotWalletRepository wallets,
            CustodyHotWalletAssetPolicyRepository policies,
            CustodyHotWalletInventoryObservationRepository observations,
            CustodyHotWalletWithdrawalReservationRepository reservations,
            CustodyAddressRepository addresses, ChainAddressRepository chainAddresses,
            WalletMpcKeyRepository keys, CustodyTenantChainTableRepository tenantChains) {
        this.wallets = wallets;
        this.policies = policies;
        this.observations = observations;
        this.reservations = reservations;
        this.addresses = addresses;
        this.chainAddresses = chainAddresses;
        this.keys = keys;
        this.tenantChains = tenantChains;
    }

    /** Empty only when no hot route has been activated. Active routes fail closed on stale or exhausted inventory. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Throwable.class)
    public Optional<String> reserveNewOrder(UUID tenantId, String chain, String asset,
                                            String orderNo, BigDecimal amount) {
        if (tenantId == null || chain == null || asset == null || orderNo == null
                || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("owned positive hot wallet withdrawal is required");
        }
        if (!tenantChains.lockWithdrawalRoutingEnabled(tenantId, chain)) {
            throw new IllegalStateException("tenant chain withdrawal routing is paused or closed");
        }
        List<CustodyHotWalletRepository.Wallet> registered=wallets.list(tenantId,chain);
        List<CustodyHotWalletRepository.Wallet> active = registered.stream()
                .filter(wallet -> "ACTIVE".equals(wallet.status())).toList();
        if (active.isEmpty()) {
            if(registered.stream().anyMatch(wallet->"QUARANTINED".equals(wallet.status())
                    || "RETIRED".equals(wallet.status())))
                throw new IllegalStateException("old hot wallet cannot fall back to legacy withdrawal source");
            return Optional.empty();
        }
        Instant now = Instant.now();
        for (var candidate : active) {
            var wallet = wallets.lock(tenantId, candidate.id()).orElseThrow();
            if (!"ACTIVE".equals(wallet.status()) || !chain.equals(wallet.chain())) continue;
            var policy = policies.list(tenantId, wallet.id()).stream()
                    .filter(row -> asset.equals(row.assetSymbol())).findFirst().orElse(null);
            var observed = observations.find(tenantId, wallet.id(), asset).orElse(null);
            if (policy == null || observed == null || observed.invalidatedAt()!=null
                    || observed.observedAt().isBefore(now.minusSeconds(60))
                    || observed.observedAt().isAfter(now.plusSeconds(5))
                    || !wallet.mpcKeyId().equals(observed.keyId())
                    || !chain.equals(observed.chain()) || !asset.equals(observed.assetSymbol())
                    || (ChainType.valueOf(chain).isEvm()
                        && (observed.observedBlockHeight()==null || observed.observedBlockHash()==null))) continue;
            try {
                amount.movePointRight(observed.decimals()).toBigIntegerExact();
            } catch (ArithmeticException precisionError) {
                throw new IllegalArgumentException("withdrawal exceeds observed hot wallet asset precision", precisionError);
            }
            var address = addresses.require(tenantId, wallet.custodyAddressId());
            var owner = chainAddresses.findOwned(tenantId, address.chainAddressId()).orElseThrow();
            var key = keys.find(tenantId, wallet.mpcKeyId()).orElseThrow();
            if (!wallet.network().equals(address.network()) || !chain.equals(address.chain())
                    || !"ACTIVE".equals(address.status()) || !"HOT_WITHDRAW".equals(owner.getWalletRole())
                    || !Boolean.TRUE.equals(owner.getEnabled()) || !wallet.mpcKeyId().equals(owner.getMpcKeyId())
                    || !address.address().equals(owner.getAddress())
                    || !address.address().equals(observed.address()) || key.state() != MpcKey.State.ACTIVE) continue;
            BigDecimal balance = new BigDecimal(observed.availableAtomic()).movePointLeft(observed.decimals());
            if (balance.compareTo(policy.maximumBalance()) > 0
                    || amount.compareTo(policy.maximumSingleWithdrawal()) > 0) continue;
            BigDecimal committed = reservations.committedExposure(tenantId, wallet.id(), asset);
            BigDecimal rolling = reservations.rollingExposure(tenantId, wallet.id(), asset, now.minusSeconds(86400));
            if (balance.subtract(committed).compareTo(amount) < 0
                    || rolling.add(amount).compareTo(policy.maximum24hWithdrawal()) > 0) continue;
            if (reservations.reserve(UUID.randomUUID(), tenantId, wallet.id(), chain, asset,
                    orderNo, address.address(), amount, observed.observedAt()) != 1) {
                throw new IllegalStateException("hot wallet withdrawal reservation already exists for this order");
            }
            return Optional.of(address.address());
        }
        throw new IllegalStateException("active hot wallets have no fresh inventory or withdrawal capacity");
    }
}

package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checks an already-bound EIP-7702 payout source at batch claim time. */
@Service
public class HotWalletPayoutClaimSourceService {
    private final CustodyHotWalletRepository wallets;
    private final CustodyAddressRepository addresses;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final WalletMpcKeyRepository keys;

    public HotWalletPayoutClaimSourceService(CustodyHotWalletRepository wallets,
            CustodyAddressRepository addresses,
            CustodyHotWalletInventoryObservationRepository observations,
            WalletMpcKeyRepository keys) {
        this.wallets = wallets;
        this.addresses = addresses;
        this.observations = observations;
        this.keys = keys;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isClaimable(UUID tenantId, String chain, String network,
                               String assetSymbol, ChainAddressRecord source) {
        if (tenantId == null || chain == null || network == null || assetSymbol == null
                || source == null || source.getMpcKeyId() == null
                || !tenantId.equals(source.getTenantId()) || !chain.equals(source.getChain())
                || !"HOT_WITHDRAW".equals(source.getWalletRole())
                || !Boolean.TRUE.equals(source.getEnabled())) return false;
        var wallet = wallets.findByKey(tenantId, chain, source.getMpcKeyId()).orElse(null);
        if (wallet == null || !"ACTIVE".equals(wallet.status())
                || !network.equals(wallet.network())) return false;
        wallet = wallets.lock(tenantId, wallet.id()).orElse(null);
        if (wallet == null || !"ACTIVE".equals(wallet.status())
                || !source.getMpcKeyId().equals(wallet.mpcKeyId())
                || !network.equals(wallet.network())) return false;
        var address = addresses.require(tenantId, wallet.custodyAddressId());
        if (!"ACTIVE".equals(address.status()) || !chain.equals(address.chain())
                || !network.equals(address.network())
                || source.getId() == null || source.getId() != address.chainAddressId()
                || !source.getAddress().equalsIgnoreCase(address.address())) return false;
        var key = keys.find(tenantId, wallet.mpcKeyId()).orElse(null);
        if (key == null || key.state() != MpcKey.State.ACTIVE) return false;
        var observed = observations.find(tenantId, wallet.id(), assetSymbol).orElse(null);
        Instant now = Instant.now();
        return observed != null && observed.invalidatedAt() == null
                && observed.observedAt() != null
                && !observed.observedAt().isBefore(now.minusSeconds(60))
                && !observed.observedAt().isAfter(now.plusSeconds(5))
                && tenantId.equals(observed.tenantId())
                && chain.equals(observed.chain()) && assetSymbol.equals(observed.assetSymbol())
                && wallet.mpcKeyId().equals(observed.keyId())
                && source.getAddress().equalsIgnoreCase(observed.address())
                && observed.observedBlockHeight() != null
                && observed.observedBlockHash() != null;
    }
}

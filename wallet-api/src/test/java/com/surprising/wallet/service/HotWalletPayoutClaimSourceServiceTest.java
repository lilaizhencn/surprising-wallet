package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotWalletPayoutClaimSourceServiceTest {
    @Test void onlyFreshActiveOwnedHotAddressCanEnterPayoutBatch() {
        UUID tenant=UUID.randomUUID(),keyId=UUID.randomUUID(),walletId=UUID.randomUUID();
        UUID addressId=UUID.randomUUID(),actor=UUID.randomUUID();
        String address="0x"+"1".repeat(40);
        Instant now=Instant.now();
        var source=ChainAddressRecord.builder().id(17L).tenantId(tenant).chain("ETH")
                .address(address).walletRole("HOT_WITHDRAW").mpcKeyId(keyId).enabled(true).build();
        var wallet=new CustodyHotWalletRepository.Wallet(walletId,tenant,addressId,keyId,
                "ETH","sepolia","Hot","ACTIVE",10,null,null,null,null,actor,now,now);
        var wallets=mock(CustodyHotWalletRepository.class);
        var addresses=mock(CustodyAddressRepository.class);
        var observations=mock(CustodyHotWalletInventoryObservationRepository.class);
        var keys=mock(WalletMpcKeyRepository.class);
        var owned=mock(CustodyAddressRecord.class);
        var observed=mock(CustodyHotWalletInventoryObservationRepository.Observation.class);
        when(wallets.findByKey(tenant,"ETH",keyId)).thenReturn(Optional.of(wallet));
        when(wallets.lock(tenant,walletId)).thenReturn(Optional.of(wallet));
        when(addresses.require(tenant,addressId)).thenReturn(owned);
        when(owned.status()).thenReturn("ACTIVE");
        when(owned.chain()).thenReturn("ETH");
        when(owned.network()).thenReturn("sepolia");
        when(owned.chainAddressId()).thenReturn(17L);
        when(owned.address()).thenReturn(address);
        when(keys.find(tenant,keyId)).thenReturn(Optional.of(new MpcKey(
                keyId,tenant,"EVM",1,1,2,"ECDSA",MpcKey.State.ACTIVE,1,"00")));
        when(observations.find(tenant,walletId,"ETH")).thenReturn(Optional.of(observed));
        when(observed.invalidatedAt()).thenReturn(null);
        when(observed.observedAt()).thenReturn(now);
        when(observed.tenantId()).thenReturn(tenant);
        when(observed.chain()).thenReturn("ETH");
        when(observed.assetSymbol()).thenReturn("ETH");
        when(observed.keyId()).thenReturn(keyId);
        when(observed.address()).thenReturn(address);
        when(observed.observedBlockHeight()).thenReturn(42L);
        when(observed.observedBlockHash()).thenReturn("0x"+"a".repeat(64));
        var service=new HotWalletPayoutClaimSourceService(wallets,addresses,observations,keys);
        assertTrue(service.isClaimable(tenant,"ETH","sepolia","ETH",source));
        when(observed.invalidatedAt()).thenReturn(now);
        assertFalse(service.isClaimable(tenant,"ETH","sepolia","ETH",source));
        when(observed.invalidatedAt()).thenReturn(null);
        when(observed.observedAt()).thenReturn(now.minusSeconds(61));
        assertFalse(service.isClaimable(tenant,"ETH","sepolia","ETH",source));
        when(observed.observedAt()).thenReturn(now);
        when(wallets.lock(tenant,walletId)).thenReturn(Optional.of(new CustodyHotWalletRepository.Wallet(
                walletId,tenant,addressId,keyId,"ETH","sepolia","Hot","QUARANTINED",10,
                actor,now,"key compromise",now,actor,now,now)));
        assertFalse(service.isClaimable(tenant,"ETH","sepolia","ETH",source));
    }
}

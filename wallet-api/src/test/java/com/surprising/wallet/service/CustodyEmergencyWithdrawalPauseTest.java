package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.Evm7702ConfigRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.CustodyReorgDeficitRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CustodyEmergencyWithdrawalPauseTest {
    @Test
    void quarantinedWalletRequiresActiveReplacementBeforeWithdrawalResume() {
        UUID tenant=UUID.randomUUID();
        var profiles=mock(ChainProfileRepository.class);
        var profile=mock(AccountChainProfile.class);
        when(profiles.listByChain("ETH")).thenReturn(java.util.List.of(profile));
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(profile));
        var hotWallets=mock(CustodyHotWalletRepository.class);
        var quarantined=mock(CustodyHotWalletRepository.Wallet.class);
        var standby=mock(CustodyHotWalletRepository.Wallet.class);
        when(quarantined.status()).thenReturn("QUARANTINED");
        when(quarantined.quarantineFenceConfirmedAt()).thenReturn(java.time.Instant.now());
        when(standby.status()).thenReturn("STANDBY");
        when(hotWallets.list(tenant,"ETH")).thenReturn(java.util.List.of(quarantined,standby));
        var chains=mock(CustodyTenantChainTableRepository.class);
        var service=new CustodyTenantChainService(chains,profiles,mock(ChainAssetRepository.class),
                mock(TokenConfigRepository.class),mock(Evm7702ConfigRepository.class),
                mock(CustodyGasAccountRepository.class),mock(CustodyAddressRepository.class),
                mock(CustodyAuditRepository.class),mock(BlockchainRuntimeService.class),hotWallets);

        assertThrows(IllegalStateException.class,()->service.setWithdrawalPaused(
                com.surprising.wallet.testutil.CustodyTestSupport.tenantAdmin(tenant),
                "ETH",false,"incident is resolved",null));
        verify(chains,never()).setWithdrawalPaused(any(),any(),eq(false),any());
    }

    @Test
    void incompleteQuarantineFenceBlocksResumeEvenWithActiveReplacement() {
        UUID tenant=UUID.randomUUID();
        var profiles=mock(ChainProfileRepository.class);
        when(profiles.listByChain("ETH")).thenReturn(java.util.List.of(mock(AccountChainProfile.class)));
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(mock(AccountChainProfile.class)));
        var hotWallets=mock(CustodyHotWalletRepository.class);
        var quarantined=mock(CustodyHotWalletRepository.Wallet.class);
        var active=mock(CustodyHotWalletRepository.Wallet.class);
        when(quarantined.status()).thenReturn("QUARANTINED");
        when(active.status()).thenReturn("ACTIVE");
        when(hotWallets.list(tenant,"ETH")).thenReturn(java.util.List.of(quarantined,active));
        var chains=mock(CustodyTenantChainTableRepository.class);
        var service=new CustodyTenantChainService(chains,profiles,mock(ChainAssetRepository.class),
                mock(TokenConfigRepository.class),mock(Evm7702ConfigRepository.class),
                mock(CustodyGasAccountRepository.class),mock(CustodyAddressRepository.class),
                mock(CustodyAuditRepository.class),mock(BlockchainRuntimeService.class),hotWallets);

        assertThrows(IllegalStateException.class,()->service.setWithdrawalPaused(
                com.surprising.wallet.testutil.CustodyTestSupport.tenantAdmin(tenant),
                "ETH",false,"four holder receipts pending",null));
        verify(chains,never()).setWithdrawalPaused(any(),any(),eq(false),any());
    }

    @Test
    void pausedChainRejectsNewWithdrawalsWithoutTouchingAssetState() {
        UUID tenant = UUID.randomUUID();
        var chains = mock(CustodyTenantChainTableRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        var assets = mock(ChainAssetRepository.class);
        var profile = mock(AccountChainProfile.class);
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(profile));
        when(chains.active(tenant, "ETH")).thenReturn(true);
        when(chains.withdrawalPaused(tenant, "ETH")).thenReturn(true);
        var service = new CustodyTenantChainService(chains, profiles, assets,
                mock(TokenConfigRepository.class), mock(Evm7702ConfigRepository.class),
                mock(CustodyGasAccountRepository.class), mock(CustodyAddressRepository.class),
                mock(CustodyAuditRepository.class), mock(BlockchainRuntimeService.class),
                mock(CustodyHotWalletRepository.class));

        assertThrows(CustodyForbiddenException.class,
                () -> service.requireWithdrawalEnabled(tenant, "ETH", "USDT"));
        verifyNoInteractions(assets);
    }

    @Test
    void pausedTenantOrderIsNotClaimedForSigning() {
        UUID tenant = UUID.randomUUID();
        var orders = mock(WithdrawalOrderRepository.class);
        var deficits = mock(CustodyReorgDeficitRepository.class);
        var chains = mock(CustodyTenantChainTableRepository.class);
        var order = mock(WithdrawalOrderRecord.class);
        when(orders.find("ETH", "order-1", tenant)).thenReturn(Optional.of(order));
        when(chains.withdrawalPaused(tenant, "ETH")).thenReturn(true);

        var claims = new WithdrawalClaimService(orders, deficits, chains);
        assertEquals(0, claims.claim(tenant, "ETH", "order-1", "0x123"));
        assertEquals(0, claims.claimDurable(tenant, "ETH", "order-1", "0x123"));
        verify(orders, never()).claimSigning(any(), any(), any(), any());
        verify(orders, never()).claimSigningDurable(any(), any(), any(), any());
        verifyNoInteractions(deficits);
    }
}

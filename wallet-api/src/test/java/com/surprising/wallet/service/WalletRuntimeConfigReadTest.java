package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletRuntimeConfigReadTest {
    @Test
    void globalChecksUseOneBulkReadAndObserveClosureOnTheNextCall() {
        var settings = mock(WalletSystemConfigRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        when(settings.globalSwitchValues()).thenReturn(Map.of(), Map.of(WalletRuntimeConfigService.GLOBAL_WALLET_ENABLED, false));
        var runtime = new WalletRuntimeConfigService(profiles, settings);
        assertTrue(runtime.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN));
        assertFalse(runtime.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN));
        assertFalse(runtime.isGlobalEnabled());
        verify(settings, times(3)).globalSwitchValues();
        verifyNoMoreInteractions(settings);
        verifyNoInteractions(profiles);
    }

    @Test
    void everyActionGuardRechecksItsProfileInsteadOfCachingThePreviousDecision() {
        var settings = mock(WalletSystemConfigRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        when(settings.globalSwitchValues()).thenReturn(Map.of());
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(AccountChainProfile.builder()
                .chain("ETH").enabled(true).withdrawEnabled(true).build()), Optional.empty());
        var runtime = new WalletRuntimeConfigService(profiles, settings);
        runtime.requireTaskEnabled("ETH", WalletRuntimeConfigService.TASK_WITHDRAW, "test action");
        assertThrows(IllegalStateException.class, () -> runtime.requireTaskEnabled("ETH", WalletRuntimeConfigService.TASK_WITHDRAW, "test action"));
        verify(settings, times(2)).globalSwitchValues();
        verify(profiles, times(2)).findAccountByChain("ETH");
    }
}

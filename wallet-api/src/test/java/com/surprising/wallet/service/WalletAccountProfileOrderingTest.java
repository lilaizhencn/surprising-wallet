package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WalletAccountProfileOrderingTest {
    @Test
    void allAccountTasksShareTheOriginalOrderAndExcludeBothUtxoFamilyNames() {
        var profiles = mock(ChainProfileRepository.class);
        var runtime = new WalletRuntimeConfigService(profiles, mock(WalletSystemConfigRepository.class));
        when(profiles.listProfiles(true)).thenReturn(List.of(
                profile("ZZ_NEW", "evm", "mainnet"), profile("ETH", "evm", "sepolia"),
                profile("BTC", "UTXO", "mainnet"), profile("DOT", "polkadot", "mainnet"),
                profile("HYPERCORE", "hypercore", "mainnet"), profile("XMR", "monero", "mainnet"),
                profile("BCH", "BITCOIN-LIKE", "mainnet"), profile("ETH", "evm", "mainnet")));
        var result = runtime.enabledAccountProfiles();
        assertEquals(List.of("XMR/mainnet", "HYPERCORE/mainnet", "ETH/mainnet", "ETH/sepolia", "DOT/mainnet", "ZZ_NEW/mainnet"),
                result.stream().map(row -> row.getChain() + "/" + row.getNetwork()).toList());
        verify(profiles).listProfiles(true);
        verifyNoMoreInteractions(profiles);
    }

    private static AccountChainProfile profile(String chain, String family, String network) {
        return AccountChainProfile.builder().chain(chain).family(family).network(network).enabled(true).build();
    }
}

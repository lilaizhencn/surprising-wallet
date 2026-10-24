package com.surprising.wallet.chain;

import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockchainRuntimeServiceTest {
    private final ChainProfileRepository profiles = mock(ChainProfileRepository.class);
    private final ChainAssetRepository assets = mock(ChainAssetRepository.class);
    private final ChainRepositoryFixture repository = ChainRepositoryFixture.mocked();
    private final ChainAddressRuntime addresses = mock(ChainAddressRuntime.class);
    private final BitcoinLikeChainRuntime bitcoinLike = mock(BitcoinLikeChainRuntime.class);
    private final BlockchainRuntimeService service = new BlockchainRuntimeService(profiles, assets, addresses, bitcoinLike);

    @ParameterizedTest
    @EnumSource(ChainType.class)
    void everyImplementedChainResolvesFromItsEnabledProfile(ChainType type) {
        when(profiles.findAccountByChain(type.name())).thenReturn(Optional.of(profile(type)));

        var runtime = service.requireRuntime(" " + type.name().toLowerCase() + " ");

        assertEquals(type, runtime.chainType());
        assertEquals(type.name(), runtime.chain());
        assertEquals("testnet", runtime.network());
        assertEquals(type.getFamily(), runtime.family());
    }

    @ParameterizedTest
    @EnumSource(ChainType.class)
    void fixedIndexGenerationReachesTheRealEngineForEveryChain(ChainType type) {
        when(profiles.findAccountByChain(type.name())).thenReturn(Optional.of(profile(type)));
        var selected = service.requireProfile(type.name());
        ChainAddressRecord expected = ChainAddressRecord.builder().id(7L).addressIndex(7L).build();
        boolean utxo = type == ChainType.BTC || type == ChainType.BCH || type == ChainType.LTC || type == ChainType.DOGE;
        if (utxo) when(bitcoinLike.generateDepositAddressAtIndex(selected, 41L, 9, 7L)).thenReturn(expected);
        else when(addresses.generateDepositAddressAtIndex(selected, 41L, 9, 7L)).thenReturn(expected);

        assertSame(expected, service.generateDepositAddressAtIndex(selected, 41L, 9, 7L));
        if (utxo) verifyNoInteractions(addresses);
        else verifyNoInteractions(bitcoinLike);
    }

    @Test
    void missingOrDisabledDatabaseProfilesCannotBeUsed() {
        assertThrows(IllegalStateException.class, () -> service.requireRuntime("ETH"));
        verifyNoInteractions(addresses, bitcoinLike);
    }

    @Test
    void bitcoinAddressValidationUsesTheConfiguredNetwork() {
        AccountChainProfile profile = profile(ChainType.BTC);
        profile.setNetwork("testnet3");
        when(profiles.findAccountByChain("BTC")).thenReturn(Optional.of(profile));
        when(repository.chainProfileRepository.findAccountByChain("BTC")).thenReturn(Optional.of(profile));
        BitcoinLikeChainRuntime runtime = new BitcoinLikeChainRuntime(
                repository.chainProfileRepository,
                repository.chainAssetRepository,
                mock(com.surprising.wallet.config.PubKeyConfig.class),
                mock(com.surprising.wallet.chain.rpc.BtcCommand.class),
                mock(com.surprising.wallet.chain.ltc.LitecoinEsploraCommand.class),
                mock(com.surprising.wallet.chain.rpc.DogeCommand.class),
                mock(com.surprising.wallet.chain.rpc.BchCommand.class),
                mock(com.surprising.wallet.repository.ChainAddressRepository.class));
        BlockchainRuntimeService configured = new BlockchainRuntimeService(profiles, assets, addresses, runtime);

        assertTrue(configured.checkAddress("BTC", " mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn "));
        assertFalse(configured.checkAddress("BTC", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"));
        assertFalse(configured.checkAddress("BTC", "not-an-address"));
        verifyNoInteractions(addresses);
    }

    private static AccountChainProfile profile(ChainType type) {
        return AccountChainProfile.builder().chain(type.name()).network("testnet")
                .family(type.getFamily()).nativeSymbol(type.name()).runtimeCurrencyId(type.ordinal())
                .enabled(true).build();
    }
}

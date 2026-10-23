package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.ChainAddressRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustodyWithdrawalAddressValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"SOLANA", "TON", "APTOS", "SUI", "TRON", "XRP", "BTC"})
    void invalidAddressesAreRejectedBeforeAnyFundsOrOrdersAreChanged(String chain) {
        ChainJdbcRepository metadata = mock(ChainJdbcRepository.class);
        when(metadata.findProfileByChain(chain)).thenReturn(Optional.of(AccountChainProfile.builder()
                .chain(chain).network("testnet").nativeSymbol(chain).build()));
        when(metadata.findBitcoinLikeProfile("BTC", "testnet")).thenReturn(Optional.of(
                com.surprising.wallet.chain.model.BitcoinLikeChainProfile.builder()
                        .chain("BTC").network("testnet").build()));
        var bitcoin = new com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime(
                metadata, null, null, null, null, null, null, null, null, null);
        var runtime = new BlockchainRuntimeService(metadata, new ChainAddressRuntime(metadata, null), bitcoin);
        var assets = mock(ChainAssetRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        when(assets.findActive(chain, chain)).thenReturn(Optional.of(Map.of("native_asset", true, "decimals", 18)));
        when(profiles.listEnabledForWithdrawal(chain)).thenReturn(List.of(Map.of("native_symbol", chain)));
        var chains = mock(ChainJdbcRepository.class);
        var custodyAddresses = mock(CustodyAddressRepository.class);
        var chainAddresses = mock(ChainAddressRepository.class);
        var balances = mock(LedgerBalanceRepository.class);
        var service = new CustodyWithdrawalExecutionService(chains, mock(WalletRuntimeConfigService.class),
                runtime, assets, profiles, mock(TokenConfigRepository.class), custodyAddresses, chainAddresses, balances);

        var error = assertThrows(IllegalArgumentException.class, () -> service.execute(
                UUID.randomUUID(), null, chain, chain, "not-an-address", BigDecimal.ONE, "WITHDRAW"));

        assertEquals("invalid " + chain + " address", error.getMessage());
        verifyNoInteractions(chains, custodyAddresses, chainAddresses, balances);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRON", "XRP"})
    void matchingTheOldAddressRegexDoesNotBypassChecksumValidation(String chain) {
        var runtime = new ChainAddressRuntime(null, null);
        String invalid = chain.equals("TRON") ? "T" + "1".repeat(33) : "r" + "1".repeat(30);
        assertFalse(runtime.checkAddress(com.surprising.wallet.common.chain.ChainType.valueOf(chain), invalid));
    }
}

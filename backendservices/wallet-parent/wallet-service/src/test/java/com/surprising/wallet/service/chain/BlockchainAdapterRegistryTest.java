package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.btc.BtcChainAdapter;
import com.surprising.wallet.service.chain.evm.EvmChainAdapter;
import com.surprising.wallet.service.chain.evm.EvmGasEstimator;
import com.surprising.wallet.service.chain.evm.EvmLogScanner;
import com.surprising.wallet.service.chain.evm.EvmNonceManager;
import com.surprising.wallet.service.chain.evm.EvmTransactionBuilder;
import com.surprising.wallet.service.chain.evm.InMemoryTokenRegistry;
import com.surprising.wallet.service.chain.tron.TronChainAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainAdapterRegistryTest {
    @Test
    void registryShouldResolveBtcEvmTronAndFutureAdapters() {
        InMemoryTokenRegistry tokenRegistry = new InMemoryTokenRegistry();
        tokenRegistry.register(TokenDefinition.builder()
                .chain("POLYGON")
                .symbol("USDT")
                .contractAddress("0xdAC17F958D2ee523a2206206994597C13D831ec7")
                .decimals(6)
                .standard("ERC20")
                .nativeAsset(false)
                .active(true)
                .build());

        EvmChainAdapter evmAdapter = new EvmChainAdapter(new EvmNonceManager(), tokenRegistry,
                new EvmGasEstimator(), new EvmTransactionBuilder(), new EvmLogScanner());
        BlockchainAdapterRegistry registry = new BlockchainAdapterRegistry(List.of(
                new BtcChainAdapter(),
                evmAdapter,
                new TronChainAdapter()
        ));

        assertEquals(ChainType.BTC, registry.require(ChainType.BTC).chainType());
        assertTrue(registry.require(ChainType.BNB).supports(ChainType.BNB));
        assertEquals("evm", registry.require(ChainType.BASE).family());
        assertEquals(ChainType.TRON, registry.require(ChainType.TRON).chainType());

        TransferQuote erc20Quote = registry.require(ChainType.POLYGON).quoteTokenTransfer(
                new TransferRequest(ChainType.POLYGON, "USDT", "0x1111111111111111111111111111111111111111",
                        "0x2222222222222222222222222222222222222222", BigDecimal.valueOf(12.34), 1, 1L, null));
        assertTrue(erc20Quote.supported());
        assertEquals("USDT", erc20Quote.assetSymbol());
        assertTrue(erc20Quote.payload() != null && !erc20Quote.payload().isBlank());
        assertThrows(UnsupportedOperationException.class, () -> registry.require(ChainType.POLYGON).scanDeposits(1L));
    }
}

package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockchainRuntimeServiceTest {

    @Test
    void requireRuntimeResolvesDbProfileThroughAdapterRegistry() {
        BlockchainRuntimeService service = new BlockchainRuntimeService(
                new BlockchainAdapterRegistry(List.of(new EvmStubAdapter())),
                new StubRepository(profile("ETH", "sepolia", "evm", "ETH", 121)),
                null);

        BlockchainRuntimeService.RuntimeChain runtime = service.requireRuntime("eth");

        assertEquals(ChainType.ETH, runtime.chainType());
        assertEquals("ETH", runtime.chain());
        assertEquals("sepolia", runtime.network());
        assertEquals("ETH", runtime.nativeSymbol());
        assertEquals("evm", runtime.adapterFamily());
        assertEquals(121, runtime.runtimeCurrencyId());
    }

    @Test
    void requireRuntimeFailsWhenChainProfileIsMissing() {
        BlockchainRuntimeService service = new BlockchainRuntimeService(
                new BlockchainAdapterRegistry(List.of(new EvmStubAdapter())),
                new StubRepository(null),
                null);

        assertThrows(IllegalStateException.class, () -> service.requireRuntime("ETH"));
    }

    private static AccountChainProfile profile(String chain, String network, String family,
                                               String nativeSymbol, int runtimeCurrencyId) {
        return AccountChainProfile.builder()
                .chain(chain)
                .network(network)
                .family(family)
                .nativeSymbol(nativeSymbol)
                .runtimeCurrencyId(runtimeCurrencyId)
                .enabled(true)
                .build();
    }

    private static final class StubRepository extends ChainJdbcRepository {
        private final AccountChainProfile profile;

        private StubRepository(AccountChainProfile profile) {
            super(null);
            this.profile = profile;
        }

        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            if (profile == null || !profile.getChain().equalsIgnoreCase(chain)) {
                return Optional.empty();
            }
            return Optional.of(profile);
        }
    }

    private static final class EvmStubAdapter implements BlockchainAdapter {
        @Override
        public ChainType chainType() {
            return ChainType.ETH;
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of(Capability.NATIVE_QUOTE);
        }

        @Override
        public boolean supports(ChainType chainType) {
            return chainType != null && chainType.isEvm();
        }

        @Override
        public String family() {
            return "evm";
        }

        @Override
        public String describe() {
            return "test evm adapter";
        }

        @Override
        public TransferQuote quoteNativeTransfer(TransferRequest request) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }
}

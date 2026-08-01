package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.model.TransferQuote;
import com.surprising.wallet.chain.model.TransferRequest;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 {@code BlockchainRuntimeServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class BlockchainRuntimeServiceTest {

    /**
     * 验证 {@code requireRuntimeResolvesDbProfileThroughAdapterRegistry} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code requireRuntimeFailsWhenChainProfileIsMissing} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void requireRuntimeFailsWhenChainProfileIsMissing() {
        BlockchainRuntimeService service = new BlockchainRuntimeService(
                new BlockchainAdapterRegistry(List.of(new EvmStubAdapter())),
                new StubRepository(null),
                null);

        assertThrows(IllegalStateException.class, () -> service.requireRuntime("ETH"));
    }

    /**
     * 验证 {@code fixedChildAddressGenerationIsDelegatedWithoutAllocatingAnotherIndex} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void fixedChildAddressGenerationIsDelegatedWithoutAllocatingAnotherIndex() {
        FixedIndexAdapter adapter = new FixedIndexAdapter();
        BlockchainRuntimeService service = new BlockchainRuntimeService(
                new BlockchainAdapterRegistry(List.of(adapter)),
                new StubRepository(profile("ETH", "sepolia", "evm", "ETH", 121)),
                null);

        Address address = service.generateDepositAddressAtIndex("ETH", 41L, 9, 1L);

        assertEquals(1, address.getIndex());
        assertEquals(41L, adapter.userId);
        assertEquals(9, adapter.biz);
        assertEquals(1L, adapter.childIndex);
    }

    /**
     * 验证 {@code profile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 测试替身 {@code StubRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class StubRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code profile}，用于承载当前测试夹具的配置或运行数据。
         */
        private final AccountChainProfile profile;

        /**
         * 验证 {@code StubRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private StubRepository(AccountChainProfile profile) {
            super(null);
            this.profile = profile;
        }

        /**
         * 验证 {@code findProfileByChain} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            if (profile == null || !profile.getChain().equalsIgnoreCase(chain)) {
                return Optional.empty();
            }
            return Optional.of(profile);
        }
    }

    /**
     * 测试辅助类 {@code EvmStubAdapter}，为相关测试提供隔离环境或共享数据。
     */
    private static class EvmStubAdapter implements BlockchainAdapter {
        /**
         * 验证 {@code chainType} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public ChainType chainType() {
            return ChainType.ETH;
        }

        /**
         * 验证 {@code capabilities} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of(Capability.NATIVE_QUOTE);
        }

        /**
         * 验证 {@code supports} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean supports(ChainType chainType) {
            return chainType != null && chainType.isEvm();
        }

        /**
         * 验证 {@code family} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String family() {
            return "evm";
        }

        /**
         * 验证 {@code describe} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String describe() {
            return "test evm adapter";
        }

        /**
         * 验证 {@code quoteNativeTransfer} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public TransferQuote quoteNativeTransfer(TransferRequest request) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    /**
     * 测试替身 {@code FixedIndexAdapter}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FixedIndexAdapter extends EvmStubAdapter {
        /**
         * 保存 {@code userId}，用于标识测试中的交易、区块或业务记录。
         */
        private long userId;
        /**
         * 保存 {@code biz}，用于承载当前测试夹具的配置或运行数据。
         */
        private int biz;
        /**
         * 保存 {@code childIndex}，用于承载当前测试夹具的配置或运行数据。
         */
        private long childIndex;

        /**
         * 验证 {@code capabilities} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.ADDRESS_GENERATION);
        }

        /**
         * 验证 {@code generateDepositAddressAtIndex} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Address generateDepositAddressAtIndex(
                ChainType chainType, long userId, int biz, long childIndex) {
            this.userId = userId;
            this.biz = biz;
            this.childIndex = childIndex;
            return Address.builder().index(Math.toIntExact(childIndex)).build();
        }
    }
}

package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code EvmChainAdapterDisabledTest} 覆盖的业务流程、边界条件和异常行为。
 */
class EvmChainAdapterDisabledTest {
    /**
     * 验证 {@code startsWithoutEnabledEvmProfiles} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void startsWithoutEnabledEvmProfiles() {
        EvmChainAdapter adapter = new EvmChainAdapter(
                null, null, null, null, null, null, new EmptyProfileRepository());

        assertTrue(adapter.supports(ChainType.ETH));
        assertThrows(IllegalArgumentException.class, () -> adapter.getProfile(ChainType.ETH));
    }

    /**
     * 测试辅助类 {@code EmptyProfileRepository}，为相关测试提供隔离环境或共享数据。
     */
    private static final class EmptyProfileRepository extends ChainJdbcRepository {
        /**
         * 验证 {@code EmptyProfileRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private EmptyProfileRepository() {
            super(null);
        }

        /**
         * 验证 {@code listEnabledChainProfiles} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<AccountChainProfile> listEnabledChainProfiles() {
            return List.of();
        }
    }
}

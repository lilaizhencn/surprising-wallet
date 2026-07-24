package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvmChainAdapterDisabledTest {
    @Test
    void startsWithoutEnabledEvmProfiles() {
        EvmChainAdapter adapter = new EvmChainAdapter(
                null, null, null, null, null, null, new EmptyProfileRepository());

        assertTrue(adapter.supports(ChainType.ETH));
        assertThrows(IllegalArgumentException.class, () -> adapter.getProfile(ChainType.ETH));
    }

    private static final class EmptyProfileRepository extends ChainJdbcRepository {
        private EmptyProfileRepository() {
            super(null);
        }

        @Override
        public List<AccountChainProfile> listEnabledChainProfiles() {
            return List.of();
        }
    }
}

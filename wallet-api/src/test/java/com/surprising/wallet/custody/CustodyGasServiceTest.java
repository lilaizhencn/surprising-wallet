package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.service.CustodyGasService;

/**
 * 验证 {@code CustodyGasServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyGasServiceTest {

    /**
     * 验证 {@code allEvmChainsShareOneTenantCollectionSubject} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void allEvmChainsShareOneTenantCollectionSubject() {
        Arrays.stream(ChainType.values())
                .filter(ChainType::isEvm)
                .forEach(chain -> assertEquals(
                        "__sw_collection__:evm",
                        CustodyGasService.collectionSubject(chain.name(), chain)));
    }

    /**
     * 验证 {@code nonEvmChainsKeepChainSpecificCollectionSubjects} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void nonEvmChainsKeepChainSpecificCollectionSubjects() {
        assertEquals("__sw_collection__:btc",
                CustodyGasService.collectionSubject("BTC", ChainType.BTC));
        assertEquals("__sw_collection__:solana",
                CustodyGasService.collectionSubject("SOLANA", ChainType.SOLANA));
    }
}

package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code PolkadotRuntimeClientTest} 覆盖的业务流程、边界条件和异常行为。
 */
class PolkadotRuntimeClientTest {
    /**
     * 验证 {@code mainnetUsesPolkadotSs58Prefix} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void mainnetUsesPolkadotSs58Prefix() {
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("DOT")
                .network("mainnet")
                .build();

        assertEquals(0, PolkadotRuntimeClient.ss58Prefix(profile));
    }

    /**
     * 验证 {@code westendUsesSubstrateSs58Prefix} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void westendUsesSubstrateSs58Prefix() {
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("DOT")
                .network("westend")
                .chainId(42L)
                .build();

        assertEquals(42, PolkadotRuntimeClient.ss58Prefix(profile));
    }

    /**
     * 验证 {@code amountPlanckParsesStringJsonValues} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void amountPlanckParsesStringJsonValues() throws Exception {
        assertEquals(new BigInteger("9997224699029"),
                PolkadotRuntimeClient.amountPlanck(new ObjectMapper().readTree("\"9997224699029\"")));
    }    @Test void missingNegativeOrOverflowingBalancesCannotBeSilentlyTreatedAsZero() {
        var json=new ObjectMapper();for(String value:java.util.List.of("null","\"-1\"","\"340282366920938463463374607431768211456\""))
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,()->PolkadotRuntimeClient.amountPlanck(json.readTree(value)));
    }

}

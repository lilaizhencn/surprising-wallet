package com.surprising.wallet.chain.cardano;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 {@code CardanoAssetUnitTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CardanoAssetUnitTest {
    /**
     * 保存 {@code POLICY}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String POLICY = "0123456789abcdef0123456789abcdef0123456789abcdef01234567";
    /**
     * 保存 {@code ASSET}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String ASSET = "55534443";

    /**
     * 验证 {@code tokenContractAcceptsPolicyDotAssetNameHex} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tokenContractAcceptsPolicyDotAssetNameHex() {
        String unit = CardanoAssetUnit.fromTokenContract(POLICY + "." + ASSET);

        assertEquals(POLICY + ASSET.toLowerCase(), unit);
        assertEquals(POLICY, CardanoAssetUnit.policyId(unit));
        assertEquals(ASSET.toLowerCase(), CardanoAssetUnit.assetNameHex(unit));
    }

    /**
     * 验证 {@code tokenContractAcceptsBlockfrostUnit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tokenContractAcceptsBlockfrostUnit() {
        String unit = CardanoAssetUnit.fromTokenContract(POLICY + ASSET);

        assertEquals(POLICY + ASSET.toLowerCase(), unit);
    }

    /**
     * 验证 {@code tokenContractRejectsOddHex} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tokenContractRejectsOddHex() {
        assertThrows(IllegalArgumentException.class,
                () -> CardanoAssetUnit.fromTokenContract(POLICY + ".abc"));
    }

    /**
     * 验证 {@code depositLogIndexSeparatesMultiAssetOutput} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void depositLogIndexSeparatesMultiAssetOutput() {
        assertEquals(30_004L, CardanoAssetUnit.depositLogIndex(3, 4));
    }
}

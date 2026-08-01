package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.service.WalletConfigManagementService.RpcNodeView;
import com.surprising.wallet.custody.service.WalletConfigManagementService;

/**
 * 验证 {@code WalletConfigManagementContractTest} 覆盖的业务流程、边界条件和异常行为。
 */
class WalletConfigManagementContractTest {
    /**
     * 验证 {@code rpcResponsesNeverExposeCredentialValues} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void rpcResponsesNeverExposeCredentialValues() {
        var names = Arrays.stream(WalletConfigManagementService.RpcNodeView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertFalse(names.contains("apiKey"));
        assertFalse(names.contains("username"));
        assertFalse(names.contains("password"));
        assertTrue(names.contains("apiKeyConfigured"));
        assertTrue(names.contains("passwordConfigured"));
    }
}

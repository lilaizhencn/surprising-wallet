package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.service.WalletConfigManagementService.RpcNodeView;
import com.surprising.wallet.custody.service.WalletConfigManagementService;

class WalletConfigManagementContractTest {
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

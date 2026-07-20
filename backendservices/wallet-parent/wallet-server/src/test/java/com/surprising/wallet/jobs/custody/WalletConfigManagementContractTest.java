package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.web.controller.WalletDashboardController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void legacyDashboardHasNoBasicAuthConfigurationRoutes() {
        var methods = Arrays.asList(WalletDashboardController.class.getDeclaredMethods());
        assertTrue(methods.stream().noneMatch(method -> method.isAnnotationPresent(PostMapping.class)));
        assertTrue(methods.stream().noneMatch(method -> method.isAnnotationPresent(PatchMapping.class)));
        assertTrue(methods.stream()
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value()))
                .noneMatch(path -> path.startsWith("/admin")));
    }
}

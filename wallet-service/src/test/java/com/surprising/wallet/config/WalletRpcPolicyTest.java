package com.surprising.wallet.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.config.WalletRpcPolicy;

class WalletRpcPolicyTest {
    @Test
    void returnsChainSpecificRequiredPurposes() {
        assertEquals(List.of("rpc"), WalletRpcPolicy.requiredPurposes("ETH", "mainnet", true));
        assertEquals(List.of("rpc", "runtime"), WalletRpcPolicy.requiredPurposes("DOT", "westend", false));
        assertEquals(List.of("rpc", "runtime", "asset_rpc"),
                WalletRpcPolicy.requiredPurposes("DOT", "westend", true));
        assertEquals(List.of("rpc", "faucet", "daemon"),
                WalletRpcPolicy.requiredPurposes("XMR", "regtest", false));
        assertEquals(List.of("info", "exchange"),
                WalletRpcPolicy.requiredPurposes("HYPERCORE", "mainnet", false));
    }

    @Test
    void appliesCredentialAndPlaceholderRules() {
        assertTrue(WalletRpcPolicy.requiresApiKey("BEARER", "HTTP_JSON_RPC"));
        assertTrue(WalletRpcPolicy.requiresApiKey("NONE", "BLOCKFROST"));
        assertTrue(WalletRpcPolicy.requiresUsernamePassword("BASIC"));
        assertTrue(WalletRpcPolicy.containsPlaceholder("https://CHANGE_ME.example"));
        assertFalse(WalletRpcPolicy.containsPlaceholder("https://rpc.example"));
    }
}

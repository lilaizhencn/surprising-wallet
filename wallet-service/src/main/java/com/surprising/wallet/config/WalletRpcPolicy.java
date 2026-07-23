package com.surprising.wallet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WalletRpcPolicy {
    private static final Set<String> API_KEY_AUTH = Set.of("BEARER", "API_KEY", "PROJECT_ID", "TOKEN");
    private static final Set<String> USER_PASSWORD_AUTH = Set.of("BASIC", "DIGEST");

    private WalletRpcPolicy() {
    }

    public static List<String> requiredPurposes(String chain, String network, boolean hasTokens) {
        if ("DOT".equalsIgnoreCase(chain)) {
            List<String> purposes = new ArrayList<>(List.of("rpc", "runtime"));
            if (hasTokens) {
                purposes.add("asset_rpc");
            }
            return List.copyOf(purposes);
        }
        if ("XMR".equalsIgnoreCase(chain) && "regtest".equalsIgnoreCase(network)) {
            return List.of("rpc", "faucet", "daemon");
        }
        if ("HYPERCORE".equalsIgnoreCase(chain)) {
            return List.of("info", "exchange");
        }
        return List.of("rpc");
    }

    public static boolean requiresApiKey(String authType, String connectionType) {
        return API_KEY_AUTH.contains(normalize(authType)) || "BLOCKFROST".equals(normalize(connectionType));
    }

    public static boolean requiresUsernamePassword(String authType) {
        return USER_PASSWORD_AUTH.contains(normalize(authType));
    }

    public static boolean containsPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = normalize(value);
        return normalized.contains("CHANGE_ME")
                || normalized.contains("YOUR_")
                || normalized.contains("<YOUR")
                || normalized.contains("REPLACE_ME")
                || normalized.contains("TODO_");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

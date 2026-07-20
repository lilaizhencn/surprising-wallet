package com.surprising.wallet.jobs.config;

import java.util.Locale;
import java.util.Set;

public final class WalletEnvironmentPolicy {
    private static final Set<String> PRODUCTION_NETWORKS = Set.of("main", "mainnet", "mainnet-beta");

    private WalletEnvironmentPolicy() {
    }

    public static boolean isProduction(String environment) {
        return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
    }

    public static boolean isProductionNetwork(String network) {
        return network != null && PRODUCTION_NETWORKS.contains(network.trim().toLowerCase(Locale.ROOT));
    }
}

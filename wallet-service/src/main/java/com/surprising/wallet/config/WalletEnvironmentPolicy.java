package com.surprising.wallet.config;

import java.util.Locale;
import java.util.Set;
/**
 * 钱包环境策略，判断运行环境是否为生产环境或生产网络。
 *
 * <p>生产网络包括 main/mainnet/mainnet-beta，生产环境标识为 prod/production。
 * 用于控制安全检查、密钥加载和生产保护逻辑。
 */
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

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
    /**
     * 定义 {@code PRODUCTION_NETWORKS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Set<String> PRODUCTION_NETWORKS = Set.of("main", "mainnet", "mainnet-beta");
    /**
     * 构造 {@code WalletEnvironmentPolicy}，初始化该组件运行所需的状态和依赖。
     */
    private WalletEnvironmentPolicy() {
    }
    /**
     * 判断 {@code isProduction} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public static boolean isProduction(String environment) {
        return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
    }
    /**
     * 判断 {@code isProductionNetwork} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public static boolean isProductionNetwork(String network) {
        return network != null && PRODUCTION_NETWORKS.contains(network.trim().toLowerCase(Locale.ROOT));
    }
}

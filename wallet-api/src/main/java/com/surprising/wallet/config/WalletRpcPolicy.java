package com.surprising.wallet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
/**
 * 钱包 RPC 策略，定义 RPC 节点的认证方式和用途管理。
 *
 * <p>提供 RPC 节点用途（purpose）的链级差异化管理（如 Polkadot 需要 rpc + runtime + asset_rpc）
 * 和鉴权类型判定（API Key / User+Password / Public / 占位符）。
 */
public final class WalletRpcPolicy {
    /**
     * 定义 {@code API_KEY_AUTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Set<String> API_KEY_AUTH = Set.of("BEARER", "API_KEY", "PROJECT_ID", "TOKEN");
    /**
     * 定义 {@code USER_PASSWORD_AUTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Set<String> USER_PASSWORD_AUTH = Set.of("BASIC", "DIGEST");
    /**
     * 构造 {@code WalletRpcPolicy}，初始化该组件运行所需的状态和依赖。
     */
    private WalletRpcPolicy() {
    }
    /**
     * 校验 {@code requiredPurposes} 对应的前置条件，不满足时抛出明确异常。
     */
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
        if ("STARKNET".equalsIgnoreCase(chain)) {
            return List.of("rpc", "scan", "broadcast");
        }
        return List.of("rpc");
    }
    /**
     * 校验 {@code requiresApiKey} 对应的前置条件，不满足时抛出明确异常。
     */
    public static boolean requiresApiKey(String authType, String connectionType) {
        return API_KEY_AUTH.contains(normalize(authType)) || "BLOCKFROST".equals(normalize(connectionType));
    }
    /**
     * 校验 {@code requiresUsernamePassword} 对应的前置条件，不满足时抛出明确异常。
     */
    public static boolean requiresUsernamePassword(String authType) {
        return USER_PASSWORD_AUTH.contains(normalize(authType));
    }
    /**
     * 校验 {@code containsPlaceholder} 对应的输入或状态，失败时抛出明确异常。
     */
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
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

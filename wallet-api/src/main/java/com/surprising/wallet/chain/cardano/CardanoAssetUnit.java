package com.surprising.wallet.chain.cardano;

import java.util.Locale;
/**
 * Cardano 资产单位（Asset Unit）工具类。
 *
 * <p>Cardano 的资产标识由 policyId（28 字节）和 assetNameHex（变长）拼接而成，
 * 原生币 ADA 使用特殊标识 "lovelace"。本类提供资产单位的规范化、
 * policyId/assetName 解析和 depositLogIndex 计算。
 */
final class CardanoAssetUnit {
    /**
     * 定义 {@code LOVELACE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final String LOVELACE = "lovelace";
    /**
     * 构造 {@code CardanoAssetUnit}，初始化该组件运行所需的状态和依赖。
     */
    private CardanoAssetUnit() {
    }
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    static String normalize(String value) {
        String unit = value == null ? "" : value.trim();
        if (unit.equalsIgnoreCase(LOVELACE)) {
            return LOVELACE;
        }
        if (unit.contains(".")) {
            String[] parts = unit.split("\\.", 2);
            return hex(parts[0], "policy id") + hex(parts.length > 1 ? parts[1] : "", "asset name");
        }
        return hex(unit, "asset unit");
    }
    /**
     * 解析 {@code fromTokenContract} 对应的输入，并转换为当前业务模型。
     */
    static String fromTokenContract(String contractAddress) {
        String unit = normalize(contractAddress);
        if (LOVELACE.equals(unit) || unit.length() < 56) {
            throw new IllegalArgumentException("Cardano token contract_address must be policyId.assetNameHex");
        }
        return unit;
    }
    /**
     * 执行 {@code policyId} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    static String policyId(String unit) {
        String normalized = normalize(unit);
        if (normalized.length() < 56) {
            throw new IllegalArgumentException("Cardano asset unit is missing policy id");
        }
        return normalized.substring(0, 56);
    }
    /**
     * 获取或查询 {@code assetNameHex} 对应的数据，并向调用方返回当前业务状态。
     */
    static String assetNameHex(String unit) {
        String normalized = normalize(unit);
        if (normalized.length() <= 56) {
            return "";
        }
        return normalized.substring(56);
    }
    /**
     * 处理 {@code depositLogIndex} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    static long depositLogIndex(int outputIndex, int assetIndex) {
        return outputIndex * 10_000L + assetIndex;
    }
    /**
     * 编码 {@code hex} 对应的数据，生成链上或接口所需的表示。
     */
    private static String hex(String value, String label) {
        String hex = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Cardano " + label + " hex length must be even");
        }
        if (!hex.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("Cardano " + label + " must be hex");
        }
        return hex;
    }
}

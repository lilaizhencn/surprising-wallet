package com.surprising.wallet.sdk.ed25519;

import java.util.Arrays;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public enum Ed25519Chain {
    /**
     * 定义 {@code SOLANA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SOLANA(501, 4),
    /**
     * 定义 {@code TON} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TON(607, 4),
    /**
     * 定义 {@code APTOS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    APTOS(637, 5),
    /**
     * 定义 {@code SUI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SUI(784, 5),
    /**
     * 定义 {@code CARDANO} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CARDANO(1815, 5),
    /**
     * 定义 {@code POLKADOT} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    POLKADOT(354, 5),
    /**
     * 定义 {@code NEAR} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    NEAR(397, 5);

    /**
     * 保存 {@code coinType}，表示链、网络、资产或代币配置。
     */
    private final int coinType;
    /**
     * 保存 {@code depth}，用于承载当前对象的运行配置或业务数据。
     */
    private final int depth;

    /**
     * 构造 {@code Ed25519Chain}，初始化该组件运行所需的状态和依赖。
     */
    Ed25519Chain(int coinType, int depth) {
        this.coinType = coinType;
        this.depth = depth;
    }

    /**
     * 获取或查询 {@code coinType} 对应的数据，并向调用方返回当前业务状态。
     */
    public int coinType() {
        return coinType;
    }

    /**
     * 获取或查询 {@code pathForUser} 对应的数据，并向调用方返回当前业务状态。
     */
    public int[] pathForUser(long userIndex) {
        if (userIndex < 0 || userIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("user index must be between 0 and 2147483647");
        }
        int[] path = depth == 4
                ? new int[]{44, coinType, (int) userIndex, 0}
                : new int[]{44, coinType, (int) userIndex, 0, 0};
        return Arrays.copyOf(path, path.length);
    }

    /**
     * 获取或查询 {@code pathForAccount} 对应的数据，并向调用方返回当前业务状态。
     */
    public int[] pathForAccount(int biz, long userId, long addressIndex) {
        if (biz < 0 || userId < 0 || userId > Integer.MAX_VALUE
                || addressIndex < 0 || addressIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("biz, user id and address index must be non-negative 32-bit values");
        }
        return new int[]{44, coinType, biz, (int) userId, (int) addressIndex};
    }

    /**
     * 获取或查询 {@code pathString} 对应的数据，并向调用方返回当前业务状态。
     */
    public String pathString(long userIndex) {
        return switch (depth) {
            case 4 -> "m/44'/" + coinType + "'/" + userIndex + "'/0'";
            case 5 -> "m/44'/" + coinType + "'/" + userIndex + "'/0'/0'";
            default -> throw new IllegalStateException("unsupported path depth " + depth);
        };
    }

    /**
     * 获取或查询 {@code pathString} 对应的数据，并向调用方返回当前业务状态。
     */
    public String pathString(int biz, long userId, long addressIndex) {
        return "m/44'/" + coinType + "'/" + biz + "'/" + userId + "'/" + addressIndex + "'";
    }
}

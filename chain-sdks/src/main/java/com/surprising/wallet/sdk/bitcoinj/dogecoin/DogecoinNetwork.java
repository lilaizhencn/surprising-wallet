package com.surprising.wallet.sdk.bitcoinj.dogecoin;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Monetary;
import org.bitcoinj.base.Network;

/**
 * 封装区块链网络参数、地址前缀和交易规则。
 */
public enum DogecoinNetwork implements Network {
    /**
     * 定义 {@code MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MAINNET("org.dogecoin.production", 30, 22, "dogecoin"),
    /**
     * 定义 {@code TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TESTNET("org.dogecoin.test", 113, 196, "dogecoin"),
    /**
     * 定义 {@code REGTEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    REGTEST("org.dogecoin.regtest", 111, 196, "dogecoin");

    /**
     * 定义 {@code UNBOUNDED_MONEY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Coin UNBOUNDED_MONEY = Coin.valueOf(Long.MAX_VALUE);

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private final String id;
    /**
     * 保存 {@code addressHeader}，表示链、网络、资产或代币配置。
     */
    private final int addressHeader;
    /**
     * 保存 {@code p2shHeader}，用于承载当前对象的运行配置或业务数据。
     */
    private final int p2shHeader;
    /**
     * 保存 {@code uriScheme}，用于承载当前对象的运行配置或业务数据。
     */
    private final String uriScheme;

    /**
     * 构造 {@code DogecoinNetwork}，初始化该组件运行所需的状态和依赖。
     */
    DogecoinNetwork(String id, int addressHeader, int p2shHeader, String uriScheme) {
        this.id = id;
        this.addressHeader = addressHeader;
        this.p2shHeader = p2shHeader;
        this.uriScheme = uriScheme;
    }

    /**
     * 获取或查询 {@code id} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String id() {
        return id;
    }

    /**
     * 执行 {@code legacyAddressHeader} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public int legacyAddressHeader() {
        return addressHeader;
    }

    /**
     * 执行 {@code legacyP2SHHeader} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public int legacyP2SHHeader() {
        return p2shHeader;
    }

    /**
     * 执行 {@code segwitAddressHrp} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public String segwitAddressHrp() {
        return "";
    }

    /**
     * 执行 {@code uriScheme} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public String uriScheme() {
        return uriScheme;
    }

    /**
     * 判断 {@code hasMaxMoney} 对应的条件是否成立，并返回明确的布尔结果。
     */
    @Override
    public boolean hasMaxMoney() {
        return false;
    }

    /**
     * 执行 {@code maxMoney} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public Monetary maxMoney() {
        return UNBOUNDED_MONEY;
    }

    /**
     * 执行 {@code exceedsMaxMoney} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public boolean exceedsMaxMoney(Monetary amount) {
        return false;
    }
}

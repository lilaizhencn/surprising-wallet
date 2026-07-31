package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Monetary;
import org.bitcoinj.base.Network;

/**
 * Bitcoin Cash（BCH）网络参数枚举，实现{@link org.bitcoinj.base.Network}接口。
 * 定义了BCH各网络（主网、测试网、回归测试网）的地址前缀、P2SH前缀和CashAddr前缀。
 *
 * <p>BCH地址体系与BTC不同：</p>
 * <ul>
 *   <li>传统地址使用与BTC相同的Base58Check格式（但网络前缀不同）</li>
 *   <li>新地址使用CashAddr格式（{@link BitcoinCashAddressCodec}），前缀分别为：
 *       {@code bitcoincash}（主网）、{@code bchtest}（测试网）、{@code bchreg}（回归测试）</li>
 *   <li>不支持SegWit地址（{@link #segwitAddressHrp()}返回空字符串）</li>
 *   <li>总量上限：2100万枚（与BTC相同）</li>
 * </ul>
 */
public enum BitcoinCashNetwork implements Network {
    /**
     * 定义 {@code MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MAINNET("org.bitcoincash.production", 0, 5, "bitcoincash"),
    /**
     * 定义 {@code TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TESTNET("org.bitcoincash.test", 111, 196, "bchtest"),
    /**
     * 定义 {@code REGTEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    REGTEST("org.bitcoincash.regtest", 111, 196, "bchreg");

    /**
     * 定义 {@code MAX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Coin MAX = Coin.COIN.multiply(21_000_000L);
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private final String id;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private final int address;
    /**
     * 保存 {@code p2sh}，用于承载当前对象的运行配置或业务数据。
     */
    private final int p2sh;
    /**
     * 保存 {@code cashPrefix}，用于承载当前对象的运行配置或业务数据。
     */
    private final String cashPrefix;

    /**
     * 构造 {@code BitcoinCashNetwork}，初始化该组件运行所需的状态和依赖。
     */
    BitcoinCashNetwork(String id, int address, int p2sh, String cashPrefix) {
        this.id = id; this.address = address; this.p2sh = p2sh; this.cashPrefix = cashPrefix;
    }
    /** 返回 BCH 网络标识，供 bitcoinj 选择对应的网络参数。 */
    @Override public String id() { return id; }

    /** 返回传统 Base58 地址的版本字节。 */
    @Override public int legacyAddressHeader() { return address; }

    /** 返回传统 P2SH 地址的版本字节。 */
    @Override public int legacyP2SHHeader() { return p2sh; }

    /** BCH 不支持 SegWit，返回空的 SegWit 地址前缀。 */
    @Override public String segwitAddressHrp() { return ""; }

    /** 返回 BCH URI 使用的 scheme。 */
    @Override public String uriScheme() { return "bitcoincash"; }

    /** BCH 使用固定的 2100 万枚总量上限。 */
    @Override public boolean hasMaxMoney() { return true; }

    /** 返回 BCH 的最大货币量。 */
    @Override public Monetary maxMoney() { return MAX; }

    /** 判断给定金额是否超过 BCH 的总量上限。 */
    @Override public boolean exceedsMaxMoney(Monetary amount) {
        return amount instanceof Coin coin && coin.isGreaterThan(MAX);
    }

    /** 返回 CashAddr 地址使用的网络前缀。 */
    public String cashPrefix() { return cashPrefix; }
}

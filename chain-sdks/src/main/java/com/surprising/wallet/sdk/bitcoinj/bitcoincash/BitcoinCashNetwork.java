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
    MAINNET("org.bitcoincash.production", 0, 5, "bitcoincash"),
    TESTNET("org.bitcoincash.test", 111, 196, "bchtest"),
    REGTEST("org.bitcoincash.regtest", 111, 196, "bchreg");

    private static final Coin MAX = Coin.COIN.multiply(21_000_000L);
    private final String id;
    private final int address;
    private final int p2sh;
    private final String cashPrefix;

    BitcoinCashNetwork(String id, int address, int p2sh, String cashPrefix) {
        this.id = id; this.address = address; this.p2sh = p2sh; this.cashPrefix = cashPrefix;
    }
    @Override public String id() { return id; }
    @Override public int legacyAddressHeader() { return address; }
    @Override public int legacyP2SHHeader() { return p2sh; }
    @Override public String segwitAddressHrp() { return ""; }
    @Override public String uriScheme() { return "bitcoincash"; }
    @Override public boolean hasMaxMoney() { return true; }
    @Override public Monetary maxMoney() { return MAX; }
    @Override public boolean exceedsMaxMoney(Monetary amount) {
        return amount instanceof Coin coin && coin.isGreaterThan(MAX);
    }
    public String cashPrefix() { return cashPrefix; }
}

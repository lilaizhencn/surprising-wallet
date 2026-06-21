package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Monetary;
import org.bitcoinj.base.Network;

public enum BitcoinCashNetwork implements Network {
    MAINNET("org.bitcoincash.production", 0, 5, "bitcoincash"),
    TESTNET("org.bitcoincash.test", 111, 196, "bchtest");

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

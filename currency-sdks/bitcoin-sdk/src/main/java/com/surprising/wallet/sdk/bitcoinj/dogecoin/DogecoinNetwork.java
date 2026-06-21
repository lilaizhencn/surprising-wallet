package com.surprising.wallet.sdk.bitcoinj.dogecoin;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Monetary;
import org.bitcoinj.base.Network;

/**
 * Dogecoin address/network metadata from Dogecoin Core 1.14.9.
 *
 * <p>Dogecoin does not activate SegWit. The empty HRP is intentional and all
 * wallet address parsing/generation is restricted to legacy Base58.</p>
 */
public enum DogecoinNetwork implements Network {
    MAINNET("org.dogecoin.production", 30, 22, "dogecoin"),
    TESTNET("org.dogecoin.test", 113, 196, "dogecoin");

    private static final Coin UNBOUNDED_MONEY = Coin.valueOf(Long.MAX_VALUE);

    private final String id;
    private final int addressHeader;
    private final int p2shHeader;
    private final String uriScheme;

    DogecoinNetwork(String id, int addressHeader, int p2shHeader, String uriScheme) {
        this.id = id;
        this.addressHeader = addressHeader;
        this.p2shHeader = p2shHeader;
        this.uriScheme = uriScheme;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int legacyAddressHeader() {
        return addressHeader;
    }

    @Override
    public int legacyP2SHHeader() {
        return p2shHeader;
    }

    @Override
    public String segwitAddressHrp() {
        return "";
    }

    @Override
    public String uriScheme() {
        return uriScheme;
    }

    @Override
    public boolean hasMaxMoney() {
        return false;
    }

    @Override
    public Monetary maxMoney() {
        return UNBOUNDED_MONEY;
    }

    @Override
    public boolean exceedsMaxMoney(Monetary amount) {
        return false;
    }
}

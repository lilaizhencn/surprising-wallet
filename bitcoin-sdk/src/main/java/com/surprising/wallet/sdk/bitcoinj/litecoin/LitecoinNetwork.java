package com.surprising.wallet.sdk.bitcoinj.litecoin;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Monetary;
import org.bitcoinj.base.Network;

/**
 * Minimal Litecoin network descriptor used by bitcoinj address and transaction
 * code. It intentionally does not try to register itself as BitcoinNetwork.
 */
public enum LitecoinNetwork implements Network {
    MAINNET("org.litecoin.production", 48, 50, "ltc", "litecoin"),
    TESTNET("org.litecoin.test", 111, 58, "tltc", "litecoin");

    private static final Coin MAX_MONEY = Coin.valueOf(84_000_000L * 100_000_000L);

    private final String id;
    private final int legacyAddressHeader;
    private final int legacyP2shHeader;
    private final String segwitAddressHrp;
    private final String uriScheme;

    LitecoinNetwork(String id, int legacyAddressHeader, int legacyP2shHeader,
                    String segwitAddressHrp, String uriScheme) {
        this.id = id;
        this.legacyAddressHeader = legacyAddressHeader;
        this.legacyP2shHeader = legacyP2shHeader;
        this.segwitAddressHrp = segwitAddressHrp;
        this.uriScheme = uriScheme;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int legacyAddressHeader() {
        return legacyAddressHeader;
    }

    @Override
    public int legacyP2SHHeader() {
        return legacyP2shHeader;
    }

    @Override
    public String segwitAddressHrp() {
        return segwitAddressHrp;
    }

    @Override
    public String uriScheme() {
        return uriScheme;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }

    @Override
    public Monetary maxMoney() {
        return MAX_MONEY;
    }

    @Override
    public boolean exceedsMaxMoney(Monetary amount) {
        return amount instanceof Coin coin && coin.isGreaterThan(MAX_MONEY);
    }
}

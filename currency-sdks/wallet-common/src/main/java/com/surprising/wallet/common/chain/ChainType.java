package com.surprising.wallet.common.chain;

import java.util.Locale;

/**
 * Canonical chain family used by the unified wallet adapters.
 */
public enum ChainType {
    BTC("bitcoin", "utxo"),
    LTC("litecoin", "utxo"),
    DOGE("dogecoin", "utxo"),
    BCH("bitcoin-cash", "utxo"),
    ETH("evm", "account"),
    BNB("evm", "account"),
    POLYGON("evm", "account"),
    ARBITRUM("evm", "account"),
    OPTIMISM("evm", "account"),
    BASE("evm", "account"),
    AVAX_C("evm", "account"),
    HYPEREVM("evm", "account"),
    MANTLE("evm", "account"),
    LINEA("evm", "account"),
    SCROLL("evm", "account"),
    HYPERCORE("hypercore", "account"),
    TRON("tron", "account"),
    XRP("xrp", "account"),
    SOLANA("solana", "account"),
    TON("ton", "account"),
    APTOS("aptos", "account"),
    SUI("sui", "object"),
    ADA("cardano", "utxo"),
    DOT("polkadot", "account"),
    NEAR("near", "account"),
    XMR("monero", "privacy");

    public static final int EVM_SHARED_BIP44_COIN_TYPE = 60;

    private final String family;
    private final String model;

    ChainType(String family, String model) {
        this.family = family;
        this.model = model;
    }

    public String getFamily() {
        return family;
    }

    public String getModel() {
        return model;
    }

    public boolean isEvm() {
        return "evm".equals(family);
    }

    public boolean isUtxo() {
        return "utxo".equals(model);
    }

    public static int derivationCoinType(String chain, int configuredCoinType) {
        try {
            return ChainType.valueOf(chain.toUpperCase(Locale.ROOT)).isEvm()
                    ? EVM_SHARED_BIP44_COIN_TYPE
                    : configuredCoinType;
        } catch (RuntimeException e) {
            return configuredCoinType;
        }
    }
}

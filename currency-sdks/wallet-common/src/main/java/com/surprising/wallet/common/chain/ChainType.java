package com.surprising.wallet.common.chain;

/**
 * Canonical chain family used by the unified wallet adapters.
 */
public enum ChainType {
    BTC("bitcoin", "utxo"),
    LTC("litecoin", "utxo"),
    ETH("evm", "account"),
    BNB("evm", "account"),
    POLYGON("evm", "account"),
    ARBITRUM("evm", "account"),
    OPTIMISM("evm", "account"),
    BASE("evm", "account"),
    AVAX_C("evm", "account"),
    TRON("tron", "account"),
    SOLANA("solana", "account"),
    TON("ton", "account");

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
}

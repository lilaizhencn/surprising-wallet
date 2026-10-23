package com.surprising.wallet.sdk.bitcoinj.core;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;

import java.util.Locale;
import java.util.Objects;

/** Canonical network selection for the four supported Bitcoin-like chains. */
public enum BitcoinLikeNetwork {
    MAINNET, TESTNET, REGTEST;

    public static BitcoinLikeNetwork parse(String value) {
        return switch (Objects.requireNonNull(value, "UTXO network is required").toLowerCase(Locale.ROOT)) {
            case "main", "mainnet" -> MAINNET;
            case "test", "testnet", "testnet3" -> TESTNET;
            case "regtest" -> REGTEST;
            default -> throw new IllegalArgumentException("unsupported UTXO network: " + value);
        };
    }

    public NetworkParameters parameters(String chain) {
        return switch (chain) {
            case "BTC" -> switch (this) {
                case MAINNET -> MainNetParams.get();
                case TESTNET -> TestNet3Params.get();
                case REGTEST -> RegTestParams.get();
            };
            case "LTC" -> switch (this) {
                case MAINNET -> LitecoinNetworkParameters.mainnet();
                case TESTNET -> LitecoinNetworkParameters.testnet();
                case REGTEST -> LitecoinNetworkParameters.regtest();
            };
            case "BCH" -> switch (this) {
                case MAINNET -> BitcoinCashNetworkParameters.mainnet();
                case TESTNET -> BitcoinCashNetworkParameters.testnet();
                case REGTEST -> BitcoinCashNetworkParameters.regtest();
            };
            case "DOGE" -> switch (this) {
                case MAINNET -> DogecoinNetworkParameters.mainnet();
                case TESTNET -> DogecoinNetworkParameters.testnet();
                case REGTEST -> DogecoinNetworkParameters.regtest();
            };
            default -> throw new IllegalArgumentException("unsupported Bitcoin-like chain: " + chain);
        };
    }
}

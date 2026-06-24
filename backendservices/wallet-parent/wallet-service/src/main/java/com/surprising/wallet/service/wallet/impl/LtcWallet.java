package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import com.surprising.wallet.service.chain.ltc.LitecoinEsploraCommand;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Litecoin testnet/mainnet wallet implementation using the BTC-like UTXO flow
 * with Litecoin-specific address/network parameters.
 */
@Slf4j
@Component
public class LtcWallet extends AbstractBtcLikeWallet implements IWallet {
    @Autowired
    LitecoinEsploraCommand command;

    @Value("${atomex.ltc.network:testnet}")
    private String network;
    private BitcoinLikeChainProfile runtimeProfile;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        super.setCommand(command);
        String profileNetwork = "mainnet".equalsIgnoreCase(network) || "main".equalsIgnoreCase(network)
                ? "mainnet" : "testnet";
        runtimeProfile = chainJdbcRepository.findBitcoinLikeProfile("LTC", profileNetwork)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for LTC/" + profileNetwork));
        currency = loadBitcoinLikeRuntimeAsset("LTC", profileNetwork);
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        return "mainnet".equalsIgnoreCase(network) || "main".equalsIgnoreCase(network)
                ? LitecoinNetworkParameters.mainnet()
                : LitecoinNetworkParameters.testnet();
    }

    @Override
    protected int getBip44CoinType() {
        return runtimeProfile.getBip44CoinType();
    }
}

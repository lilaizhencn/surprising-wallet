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

    private BitcoinLikeChainProfile runtimeProfile;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        super.setCommand(command);
        runtimeProfile = chainJdbcRepository.findProfileByChain("LTC")
                .flatMap(profile -> chainJdbcRepository.findBitcoinLikeProfile("LTC", profile.getNetwork()))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for LTC"));
        if ("regtest".equalsIgnoreCase(runtimeProfile.getNetwork())) {
            throw new IllegalStateException("LTC regtest network parameters are not implemented");
        }
        currency = loadBitcoinLikeRuntimeAsset("LTC", runtimeProfile.getNetwork());
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        return "mainnet".equalsIgnoreCase(runtimeProfile.getNetwork()) || "main".equalsIgnoreCase(runtimeProfile.getNetwork())
                ? LitecoinNetworkParameters.mainnet()
                : LitecoinNetworkParameters.testnet();
    }

    @Override
    protected int getBip44CoinType() {
        return runtimeProfile.getBip44CoinType();
    }
}

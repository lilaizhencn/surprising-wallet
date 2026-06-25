package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.BtcCommand;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;


/**
 * @author lilaizhen
 * @data 27/03/2018
 */
@Slf4j
@Component
public class BtcWallet extends AbstractBtcLikeWallet implements IWallet {


    @Autowired
    BtcCommand command;
    private BitcoinLikeChainProfile runtimeProfile;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        super.setCommand(command);
        runtimeProfile = chainJdbcRepository.findProfileByChain("BTC")
                .flatMap(profile -> chainJdbcRepository.findBitcoinLikeProfile("BTC", profile.getNetwork()))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for BTC"));
        currency = loadBitcoinLikeRuntimeAsset("BTC", runtimeProfile.getNetwork());
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }


    @Override
    public NetworkParameters getNetworkParameters() {
        if ("main".equalsIgnoreCase(runtimeProfile.getNetwork())
                || "mainnet".equalsIgnoreCase(runtimeProfile.getNetwork())) {
            return MainNetParams.get();
        }
        if ("regtest".equalsIgnoreCase(runtimeProfile.getNetwork())) {
            return RegTestParams.get();
        }
        return TestNet3Params.get();
    }


}

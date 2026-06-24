package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.BtcCommand;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
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

    @PostConstruct
    public void init() {
        super.setCommand(command);
    }

    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.BTC;
    }


    @Override
    public NetworkParameters getNetworkParameters() {
        return TestNet3Params.get();
    }


}

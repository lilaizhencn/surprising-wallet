package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.LtcCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
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
    LtcCommand command;

    @Value("${atomex.ltc.network:testnet}")
    private String network;

    @PostConstruct
    public void init() {
        super.setCommand(command);
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.LTC;
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        return "mainnet".equalsIgnoreCase(network) || "main".equalsIgnoreCase(network)
                ? LitecoinNetworkParameters.mainnet()
                : LitecoinNetworkParameters.testnet();
    }
}

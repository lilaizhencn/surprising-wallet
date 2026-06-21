package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.common.currency.CurrencyEnum;
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

    @PostConstruct
    public void init() {
        super.setCommand(command);
        String profileNetwork = "mainnet".equalsIgnoreCase(network) || "main".equalsIgnoreCase(network)
                ? "mainnet" : "testnet";
        runtimeProfile = chainJdbcRepository.findBitcoinLikeProfile("LTC", profileNetwork)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for LTC/" + profileNetwork));
        if (runtimeProfile.getRuntimeCurrencyId() != CurrencyEnum.LTC.getIndex()) {
            throw new IllegalStateException("LTC runtime currency id conflicts with legacy routing id");
        }
        if (runtimeProfile.getBip44CoinType() != CurrencyEnum.LTC.getBip44CoinType()) {
            throw new IllegalStateException("LTC BIP44 coin type conflicts with legacy compatibility metadata");
        }
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

    @Override
    protected int getBip44CoinType() {
        return runtimeProfile.getBip44CoinType();
    }
}

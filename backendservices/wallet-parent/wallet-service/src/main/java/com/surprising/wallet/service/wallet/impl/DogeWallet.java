package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.DogeCommand;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import jakarta.annotation.PostConstruct;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

/**
 * Dogecoin legacy P2SH 2-of-3 wallet.
 */
@Component
public class DogeWallet extends AbstractBtcLikeWallet implements IWallet {
    @Autowired
    private DogeCommand dogeCommand;

    @Value("${atomex.doge.network:testnet}")
    private String network;

    private BitcoinLikeChainProfile runtimeProfile;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        super.setCommand(dogeCommand);
        String profileNetwork = isMainnet() ? "mainnet" : isRegtest() ? "regtest" : "testnet";
        runtimeProfile = chainJdbcRepository.findBitcoinLikeProfile("DOGE", profileNetwork)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for DOGE/" + profileNetwork));
        currency = loadBitcoinLikeRuntimeAsset("DOGE", profileNetwork);
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        if (isMainnet()) {
            return DogecoinNetworkParameters.mainnet();
        }
        return isRegtest()
                ? DogecoinNetworkParameters.regtest()
                : DogecoinNetworkParameters.testnet();
    }

    @Override
    protected int getBip44CoinType() {
        return runtimeProfile.getBip44CoinType();
    }

    @Override
    protected long getWithdrawConfirmationThreshold() {
        return runtimeProfile.getWithdrawConfirmations();
    }

    @Override
    protected Address buildAddress(Long userId, Integer biz, int index) {
        PubKeyConfig.AddressMetadata metadata = pubKeyConfig.genLegacyThreeTwoAddressMetadata(
                getNetworkParameters(), getBip44CoinType(), userId.intValue(), biz, index);
        return Address.builder()
                .address(metadata.getAddress())
                .network(getNetworkName())
                .scriptType("P2SH")
                .redeemScript(metadata.getRedeemScript())
                .witnessScript("")
                .derivationPath(metadata.getPath())
                .publicKeys(metadata.getPublicKeys())
                .biz(biz)
                .currency(getCurrency().getName())
                .userId(userId)
                .index(index)
                .balance(BigDecimal.ZERO)
                .nonce(0)
                .status((byte) com.surprising.wallet.common.utils.Constants.WAITING)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }

    private boolean isMainnet() {
        return "mainnet".equalsIgnoreCase(network) || "main".equalsIgnoreCase(network);
    }

    private boolean isRegtest() {
        return "regtest".equalsIgnoreCase(network);
    }
}

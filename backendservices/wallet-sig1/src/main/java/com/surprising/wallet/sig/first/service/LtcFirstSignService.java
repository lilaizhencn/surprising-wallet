package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.stereotype.Component;

/**
 * First signature service for Litecoin P2WSH transactions. It reuses the BTC
 * witness signing flow but supplies Litecoin network and fee/dust policy.
 */
@Component
@Slf4j
public class LtcFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {
    @Override
    public String chain() {
        return "LTC";
    }

    @Override
    protected NetworkParameters getNetworkParameters() {
        return LitecoinNetworkParameters.testnet();
    }

    @Override
    protected long defaultFeeRate() {
        return LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
    }

    @Override
    protected long dustThresholdSat() {
        return LitecoinFeePolicy.DUST_THRESHOLD_LITOSHI;
    }
}

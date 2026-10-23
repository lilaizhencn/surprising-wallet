package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LtcFirstSignService extends AbstractBtcLikeFirstSign {
    public LtcFirstSignService(PubKeyConfig publicKeys,
                                  @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys) {
        super(publicKeys, keys);
    }
    @Override public String chain() { return "LTC"; }
    @Override protected long defaultFeeRate() { return LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE; }
    @Override protected long dustThreshold() { return LitecoinFeePolicy.DUST_THRESHOLD_LITOSHI; }
}

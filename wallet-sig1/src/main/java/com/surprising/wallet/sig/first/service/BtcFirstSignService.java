package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BtcFirstSignService extends AbstractBtcLikeFirstSign {
    public BtcFirstSignService(PubKeyConfig publicKeys,
                                  @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys) {
        super(publicKeys, keys);
    }
    @Override public String chain() { return "BTC"; }
}

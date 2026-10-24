package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.sig.second.Sig2KeyDerivation;

import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LtcSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {
    public LtcSecondSignService(Sig2KeyDerivation keyDerivation) {
        super(keyDerivation);
    }

    @Override
    public String chain() {
        return "LTC";
    }
}

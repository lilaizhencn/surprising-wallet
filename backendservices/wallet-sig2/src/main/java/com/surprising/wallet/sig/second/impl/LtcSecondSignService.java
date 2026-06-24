package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Second signature service for Litecoin P2WSH transactions.
 */
@Component
@Slf4j
public class LtcSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {
    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.LTC;
    }
}

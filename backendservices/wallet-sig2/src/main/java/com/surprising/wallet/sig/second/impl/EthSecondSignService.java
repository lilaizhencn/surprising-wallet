package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.sig.second.ISignService;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
public class EthSecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    @Override
    public RuntimeAsset getCurrency() {
        return RuntimeAsset.ETH;
    }
}

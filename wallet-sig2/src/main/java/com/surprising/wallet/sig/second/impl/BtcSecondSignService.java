package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BtcSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {
    @Override
    public String chain() {
        return "BTC";
    }
}

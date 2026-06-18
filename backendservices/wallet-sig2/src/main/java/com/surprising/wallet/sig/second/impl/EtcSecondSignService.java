package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.sig.second.ISignService;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
public class EtcSecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.ETC;
    }
}

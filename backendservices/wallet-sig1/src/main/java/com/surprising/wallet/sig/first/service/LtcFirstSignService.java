package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.currency.CurrencyEnum;
//import deleted;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.stereotype.Component;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class LtcFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {
    /**
     * 获取currency对应的签名类
     */
    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.LTC;
    }

    @Override
    protected NetworkParameters getNetworkParameters() {
        return org.bitcoinj.params.MainNetParams.get();
    }
}

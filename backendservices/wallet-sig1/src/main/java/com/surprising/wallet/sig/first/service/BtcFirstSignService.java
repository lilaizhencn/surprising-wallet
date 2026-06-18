package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.currency.CurrencyEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class BtcFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {
    /**
     * 获取currency对应的签名类
     *
     * @return
     */
    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.BTC;
    }
}

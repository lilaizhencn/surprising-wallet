package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.currency.CurrencyEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author atomex
 * @data 27/03/2018
 */

@Slf4j
@Component
public class WalletContext {
    @Autowired
    private List<IWallet> cachedWallet;

    public IWallet getWallet(CurrencyEnum currency) {
        if (CurrencyEnum.isErc20(currency)) {
            currency = CurrencyEnum.ERC20;
        }
        currency = CurrencyEnum.toMainCurrency(currency);

        for (IWallet wallet : cachedWallet) {
            if (wallet.getCurrency() == currency) {
                return wallet;
            }
        }
        return null;
    }
}

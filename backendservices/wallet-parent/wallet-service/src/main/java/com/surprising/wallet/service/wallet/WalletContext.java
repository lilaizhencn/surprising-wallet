package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.RuntimeAsset;
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

    public IWallet getWallet(RuntimeAsset currency) {
        IWallet wallet = findWallet(currency);
        if (wallet != null) {
            return wallet;
        }

        RuntimeAsset mainCurrency = RuntimeAsset.toMainCurrency(currency);
        if (mainCurrency != currency) {
            return findWallet(mainCurrency);
        }
        return null;
    }

    private IWallet findWallet(RuntimeAsset currency) {
        for (IWallet wallet : cachedWallet) {
            if (wallet.getCurrency().sameAsset(currency)) {
                return wallet;
            }
        }
        return null;
    }
}

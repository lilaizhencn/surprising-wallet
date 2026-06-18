package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/**
 * @author lilaizhen
 * @data 02/04/2018
 */

public interface ISignService {

    /**
     * 交易签名
     *
     * @param transaction
     */
    void signTransaction(WithdrawTransaction transaction);

    /**
     * 获取currency对应的签名类
     *
     * @return
     */
    CurrencyEnum getCurrency();
}

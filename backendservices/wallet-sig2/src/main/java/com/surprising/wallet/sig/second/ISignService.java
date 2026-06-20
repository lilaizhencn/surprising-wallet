package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/**
 * @author atomex
 */

public interface ISignService {
    /**
     * 返回签名结果
     *
     * @param transaction
     */
    String signTransaction(WithdrawTransaction transaction);

    /**
     * 获取currency对应的签名类
     */
    CurrencyEnum getCurrency();
}

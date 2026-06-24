package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.service.CrudService;
import com.surprising.wallet.common.pojo.CurrencyBalance;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;

/**
 * Legacy currency_balance aggregate. Runtime asset balances are read from
 * ledger_balance for DB Asset Model chains.
 *
 * @author lilaizhen
 * @date 2018-04-26
 */
@Deprecated
public interface CurrencyBalanceService
        extends CrudService<CurrencyBalance, CurrencyBalanceExample, Integer> {
}

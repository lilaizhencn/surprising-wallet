package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.service.CrudService;
import com.surprising.wallet.common.pojo.CurrencyBalance;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-04-26
 */
public interface CurrencyBalanceService
        extends CrudService<CurrencyBalance, CurrencyBalanceExample, Integer> {
}

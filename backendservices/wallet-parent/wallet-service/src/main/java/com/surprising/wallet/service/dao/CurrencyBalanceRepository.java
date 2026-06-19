package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.data.CrudRepository;
import com.surprising.wallet.common.pojo.CurrencyBalance;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import org.springframework.stereotype.Repository;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-04-26
 */
@Repository
public interface CurrencyBalanceRepository
        extends CrudRepository<CurrencyBalance, CurrencyBalanceExample, Integer> {
}
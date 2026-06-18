package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.service.AbstractCrudService;
import com.surprising.wallet.common.pojo.CurrencyBalance;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import com.surprising.wallet.service.dao.CurrencyBalanceRepository;
import com.surprising.wallet.service.service.CurrencyBalanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-04-26
 */
@Slf4j
@Service
public class CurrencyBalanceServiceImpl
        extends AbstractCrudService<CurrencyBalanceRepository, CurrencyBalance, CurrencyBalanceExample, Integer>
        implements CurrencyBalanceService {

    @Autowired
    private CurrencyBalanceRepository currencyBalanceRepos;

    @Override
    protected CurrencyBalanceExample getPageExample(String fieldName, String keyword) {
        CurrencyBalanceExample example = new CurrencyBalanceExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }
}
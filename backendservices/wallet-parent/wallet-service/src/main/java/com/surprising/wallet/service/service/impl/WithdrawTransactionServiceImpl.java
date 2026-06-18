package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;
import com.surprising.wallet.service.dao.WithdrawTransactionRepository;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-04-01
 */
@Slf4j
@Service
public class WithdrawTransactionServiceImpl
        extends AbstractCrudService<WithdrawTransactionRepository, WithdrawTransaction, WithdrawTransactionExample, Integer>
        implements WithdrawTransactionService {

    @Autowired
    private WithdrawTransactionRepository withdrawTransactionRepos;

    @Override
    protected WithdrawTransactionExample getPageExample(String fieldName, String keyword) {
        WithdrawTransactionExample example = new WithdrawTransactionExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }

    @Override
    public WithdrawTransaction getByTxId(String txid, CurrencyEnum currencyEnum) {
        ShardTable table = ShardTable.builder().prefix(currencyEnum.getName()).build();
        WithdrawTransactionExample withExam = new WithdrawTransactionExample();
        withExam.createCriteria().andTxIdEqualTo(txid);
        return getOneByExample(withExam, table).get();
    }


}
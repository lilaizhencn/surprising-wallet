package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.dao.AccountTransactionRepository;
import com.surprising.wallet.service.service.AccountTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.math.BigDecimal;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-04-12
 */
@Slf4j
@Service
public class AccountTransactionServiceImpl
        extends AbstractCrudService<AccountTransactionRepository, AccountTransaction, AccountTransactionExample, Long>
        implements AccountTransactionService {

    @Autowired
    private AccountTransactionRepository accountTransactionRepos;

    @Override
    protected AccountTransactionExample getPageExample(String fieldName, String keyword) {
        AccountTransactionExample example = new AccountTransactionExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }

    @Override
    public BigDecimal getTotalBalance(AccountTransactionExample example, ShardTable table) {
        BigDecimal totalBalance = accountTransactionRepos.getTotalBalance(example, table);
        return totalBalance;
    }

    @Override
    public int addOnDuplicateKey(AccountTransaction tx, ShardTable table) {
        AccountTransactionExample example = new AccountTransactionExample();
        example.createCriteria().andTxIdEqualTo(tx.getTxId());
        AccountTransaction exist = getOneByExample(example, table).get();
        if (ObjectUtils.isEmpty(exist)) {
            return accountTransactionRepos.insertOnDuplicateKey(tx, table);
        } else {
            return 1;
        }
    }
}
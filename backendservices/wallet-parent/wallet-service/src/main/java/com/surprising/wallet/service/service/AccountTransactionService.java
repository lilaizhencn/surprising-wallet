package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.service.criteria.AccountTransactionExample;

import java.math.BigDecimal;


/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-04-12
 */
public interface AccountTransactionService
        extends CrudService<AccountTransaction, AccountTransactionExample, Long> {
    BigDecimal getTotalBalance(AccountTransactionExample example, ShardTable table);

    int addOnDuplicateKey(AccountTransaction tx, ShardTable table);
}

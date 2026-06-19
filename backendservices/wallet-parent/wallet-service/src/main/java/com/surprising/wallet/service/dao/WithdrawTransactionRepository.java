package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.sharding.data.CrudRepository;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;
import org.springframework.stereotype.Repository;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-04-01
 */
@Repository
public interface WithdrawTransactionRepository
        extends CrudRepository<WithdrawTransaction, WithdrawTransactionExample, Integer> {
}
package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.data.CrudRepository;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-04-12
 */
@Repository
public interface AccountTransactionRepository
        extends CrudRepository<AccountTransaction, AccountTransactionExample, Long> {
    BigDecimal getTotalBalance(@Param("example") AccountTransactionExample example, @Param("shardTable") ShardTable table);

    int insertOnDuplicateKey(@Param("record") AccountTransaction tx, @Param("shardTable") ShardTable table);
}
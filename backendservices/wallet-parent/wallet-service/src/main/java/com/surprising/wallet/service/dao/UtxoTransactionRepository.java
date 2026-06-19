package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.data.CrudRepository;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-03-31
 */
@Repository
public interface UtxoTransactionRepository
        extends CrudRepository<UtxoTransaction, UtxoTransactionExample, Long> {
    /**
     * 获取utxo的balance总和
     *
     * @return
     */
    BigDecimal getTotalBalance(@Param("example") UtxoTransactionExample example, @Param("shardTable") ShardTable table);

    int insertOnDuplicateKey(@Param("record") UtxoTransaction tx, @Param("shardTable") ShardTable table);

}
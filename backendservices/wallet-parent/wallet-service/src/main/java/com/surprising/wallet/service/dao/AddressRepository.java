package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.data.CrudRepository;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.service.criteria.AddressExample;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-03-27
 */
@Repository
public interface AddressRepository
        extends CrudRepository<Address, AddressExample, Integer> {
    Address selectAndLockOneByExample(@Param("example") AddressExample example, @Param("shardTable") ShardTable table);

    BigDecimal getTotalBalance(@Param("example") AddressExample example, @Param("shardTable") ShardTable table);

}
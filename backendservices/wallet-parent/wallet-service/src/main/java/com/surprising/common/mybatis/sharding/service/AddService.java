package com.surprising.common.mybatis.sharding.service;

import com.surprising.common.mybatis.sharding.ShardTable;

import java.util.List;

/**
 * @param <T> Po
 * @author polarisex
 * @date 2017/12/09
 */
public interface AddService<T> {
    /**
     * @param record     pojo记录
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int add(T record, ShardTable shardTable);

    /**
     * @param records    pojo记录集
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int batchAdd(List<T> records, ShardTable shardTable);

    /**
     * 使用mysql on duplicate key 语句插入与修改
     *
     * @param records
     * @param shardTable 分表对象
     * @return
     */
    int batchAddOnDuplicateKey(List<T> records, ShardTable shardTable);
}

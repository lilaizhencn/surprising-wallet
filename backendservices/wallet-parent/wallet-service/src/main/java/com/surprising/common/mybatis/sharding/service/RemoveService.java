package com.surprising.common.mybatis.sharding.service;

import com.surprising.common.mybatis.sharding.ShardTable;

import java.util.List;

/**
 * @param <T> Po
 * @param <U> Example
 * @param <K> key字段数据类型(Integer,Long,String等)
 * @author polarisex
 * @date 2017/12/09
 */
public interface RemoveService<T, U, K> {
    /**
     * @param id         id
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int removeById(K id, ShardTable shardTable);

    /**
     * @param example    pojo记录
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int removeByExample(U example, ShardTable shardTable);

    /**
     * @param records    pojo记录集
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int removeIn(List<T> records, ShardTable shardTable);
}

package com.surprising.common.mybatis.sharding.data;

import com.surprising.common.mybatis.sharding.ShardTable;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @param <T> Po
 * @param <U> Example
 * @param <K> Key字段数据类型(Integer,Long,String等)
 * @author polarisex
 * @date 2017/12/09
 */
public interface DeleteRepository<T, U, K> {
    /**
     * 根据主键删除记录
     *
     * @param id         id主键值
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int deleteById(@Param("id") K id, @Param("shardTable") ShardTable shardTable);

    /**
     * 根据条件删除记录
     *
     * @param example    查询Example条件参数
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int deleteByExample(@Param("example") U example, @Param("shardTable") ShardTable shardTable);

    /**
     * @param records    pojo记录集
     * @param shardTable 分表对象
     * @return 影响的记录数
     */
    int deleteIn(@Param("records") List<T> records, @Param("shardTable") ShardTable shardTable);
}


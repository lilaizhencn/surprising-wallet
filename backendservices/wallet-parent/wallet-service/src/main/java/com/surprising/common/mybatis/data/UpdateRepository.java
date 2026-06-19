package com.surprising.common.mybatis.data;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @param <T> Po
 * @param <U> Example
 * @author polarisex
 * @date 2017/12/09
 */
public interface UpdateRepository<T, U> {
    /**
     * 根据主键更新用户信息
     *
     * @param record
     * @return 影响的记录数
     */
    int updateById(@Param("record") T record);

    /**
     * 根据条件更新数据
     *
     * @param record
     * @param example
     * @return 影响的记录数
     */
    int updateByExample(@Param("record") T record, @Param("example") U example);

    /**
     * @param records
     * @return 影响的记录数
     */
    int batchUpdate(@Param("records") List<T> records);
}

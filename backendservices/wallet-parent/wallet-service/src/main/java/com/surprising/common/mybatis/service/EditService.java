package com.surprising.common.mybatis.service;

import java.util.List;

/**
 * @param <T> Po
 * @param <U> Example
 * @author polarisex
 * @date 2017/12/09
 */
public interface EditService<T, U> {
    /**
     * 根据主键更新用户信息
     *
     * @param record
     * @return 影响的记录数
     */
    int editById(T record);

    /**
     * 根据条件更新数据
     *
     * @param record
     * @param example
     * @return 影响的记录数
     */
    int editByExample(T record, U example);

    /**
     * @param records
     * @return 影响的记录数
     */
    int batchEdit(List<T> records);
}

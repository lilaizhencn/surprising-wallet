package com.surprising.common.mybatis.service;

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
     * @param id
     * @return
     */
    int removeById(K id);

    /**
     * @param example
     * @return
     */
    int removeByExample(U example);

    /**
     * @param records
     * @return
     */
    int removeIn(List<T> records);
}

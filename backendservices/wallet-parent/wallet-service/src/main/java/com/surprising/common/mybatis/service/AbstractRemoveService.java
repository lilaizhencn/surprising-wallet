package com.surprising.common.mybatis.service;

import com.surprising.common.mybatis.data.DeleteRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * @param <Dao>
 * @param <Po>
 * @param <Example>
 * @param <Type>    Key字段数据类型(Integer,Long,String等)
 * @author polarisex
 * @date 2017/12/09
 */
public abstract class AbstractRemoveService<Dao extends DeleteRepository<Po, Example, Type>, Po, Example, Type>
        implements RemoveService<Po, Example, Type> {
    @Autowired
    protected Dao dao;

    @Override
    public int removeById(final Type id) {
        return this.dao.deleteById(id);
    }

    @Override
    public int removeByExample(final Example example) {
        return this.dao.deleteByExample(example);
    }

    @Override
    public int removeIn(final List<Po> records) {
        return this.dao.deleteIn(records);
    }
}

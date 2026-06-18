package com.surprising.common.mybatis.service;

import com.surprising.common.mybatis.data.UpdateRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * @param <Dao>
 * @param <Po>
 * @param <Example>
 * @author polarisex
 * @date 2017/12/09
 */
public abstract class AbstractEditService<Dao extends UpdateRepository<Po, Example>, Po, Example>
        implements EditService<Po, Example> {
    @Autowired
    protected Dao dao;

    @Override
    public int editById(final Po record) {
        return this.dao.updateById(record);
    }

    @Override
    public int editByExample(final Po record, final Example example) {
        return this.dao.updateByExample(record, example);
    }

    @Override
    public int batchEdit(final List<Po> records) {
        return this.dao.batchUpdate(records);
    }
}

package com.surprising.common.mybatis.service;

import com.surprising.common.mybatis.data.InsertRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * @param <Dao>
 * @param <Po>
 * @author polarisex
 * @date 2017/12/09
 */
public abstract class AbstractAddService<Dao extends InsertRepository<Po>, Po> implements AddService<Po> {
    @Autowired
    protected Dao dao;

    @Override
    public int add(final Po record) {
        return this.dao.insert(record);
    }

    @Override
    public int batchAdd(final List<Po> records) {
        return this.dao.batchInsert(records);
    }

    @Override
    public int batchAddOnDuplicateKey(final List<Po> records) {
        return this.dao.batchInsertOnDuplicateKey(records);
    }
}

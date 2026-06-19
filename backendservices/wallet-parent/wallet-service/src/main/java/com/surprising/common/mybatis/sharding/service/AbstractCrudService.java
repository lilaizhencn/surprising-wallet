package com.surprising.common.mybatis.sharding.service;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.data.CrudRepository;

import java.util.List;

/**
 * 基本增删改查(CRUD)数据访问服务基类
 *
 * @param <Dao>
 * @param <Po>
 * @param <Example>
 * @author polarisex
 * @date 2017/12/09
 */
public abstract class AbstractCrudService<Dao extends CrudRepository<Po, Example, Type>, Po, Example, Type>
        extends AbstractGetService<Dao, Po, Example, Type>
        implements CrudService<Po, Example, Type> {

    @Override
    public int add(final Po record, final ShardTable shardTable) {
        return this.dao.insert(record, shardTable);
    }

    @Override
    public int batchAdd(final List<Po> records, final ShardTable shardTable) {
        return this.dao.batchInsert(records, shardTable);
    }

    @Override
    public int batchAddOnDuplicateKey(final List<Po> records, final ShardTable shardTable) {
        return this.dao.batchInsertOnDuplicateKey(records, shardTable);
    }

    @Override
    public int editById(final Po record, final ShardTable shardTable) {
        return this.dao.updateById(record, shardTable);
    }

    @Override
    public int editByExample(final Po record, final Example example, final ShardTable shardTable) {
        return this.dao.updateByExample(record, example, shardTable);
    }

    @Override
    public int batchEdit(final List<Po> records, final ShardTable shardTable) {
        return this.dao.batchUpdate(records, shardTable);
    }

    @Override
    public int removeById(final Type id, final ShardTable shardTable) {
        return this.dao.deleteById(id, shardTable);
    }

    @Override
    public int removeByExample(final Example example, final ShardTable shardTable) {
        return this.dao.deleteByExample(example, shardTable);
    }

    @Override
    public int removeIn(final List<Po> records, final ShardTable shardTable) {
        return this.dao.deleteIn(records, shardTable);
    }
}

package com.surprising.common.mybatis.sharding.service;

import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.data.SelectRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * @param <Dao>
 * @param <Po>
 * @param <Example>
 * @param <Type>    Key字段数据类型(Integer,Long,String等)
 * @author polarisex
 * @date 2017/12/09
 */
public abstract class AbstractGetService<Dao extends SelectRepository<Po, Example, Type>, Po, Example, Type>
        implements GetService<Po, Example, Type> {
    @Autowired
    protected Dao dao;

    @Override
    public boolean exists(Example example, ShardTable shardTable) {
        return dao.countByExample(example, shardTable) > 0;
    }

    @Override
    public Optional<Po> getById(Type id, ShardTable shardTable) {
        return dao.selectById(id, shardTable);
    }

    @Override
    public List<Po> getByExample(Example example, ShardTable shardTable) {
        return dao.selectByExample(example, shardTable);
    }

    @Override
    public List<Po> getAll(ShardTable shardTable) {
        return dao.selectByExample(null, shardTable);
    }

    @Override
    public Optional<Po> getOneByExample(Example example, ShardTable shardTable) {
        return dao.selectOneByExample(example, shardTable);
    }

    @Override
    public List<Po> getIn(List<Po> records, ShardTable shardTable) {
        return dao.selectIn(records, shardTable);
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo, ShardTable shardTable) {
        return getByPage(pageInfo, "", "", shardTable);
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo, String fieldName, String keyword,
                              ShardTable shardTable) {
        if (StringUtils.isBlank(fieldName)) {
            return getByPage(pageInfo, null, shardTable);
        }
        return getByPage(pageInfo, getPageExample(fieldName, keyword), shardTable);
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo, Example example, ShardTable shardTable) {
        pageInfo.setTotals(dao.countByPager(pageInfo, example, shardTable));
        return dao.selectByPager(pageInfo, example, shardTable);
    }

    protected abstract Example getPageExample(String fieldName, String keyword);
}

package com.surprising.common.mybatis.service;

import com.surprising.common.mybatis.data.SelectRepository;
import com.surprising.common.mybatis.pager.PageInfo;
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
    public boolean exists(Example example) {
        return dao.countByExample(example) > 0;
    }

    @Override
    public Optional<Po> getById(Type id) {
        return dao.selectById(id);
    }

    @Override
    public List<Po> getByExample(Example example) {
        return dao.selectByExample(example);
    }

    @Override
    public List<Po> getAll() {
        return dao.selectByExample(null);
    }

    @Override
    public Optional<Po> getOneByExample(Example example) {
        return dao.selectOneByExample(example);
    }

    @Override
    public List<Po> getIn(List<Po> records) {
        return dao.selectIn(records);
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo) {
        return getByPage(pageInfo, "", "");
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo, String fieldName, String keyword) {
        if (StringUtils.isBlank(fieldName)) {
            return getByPage(pageInfo, null);
        }
        return getByPage(pageInfo, getPageExample(fieldName, keyword));
    }

    @Override
    public List<Po> getByPage(PageInfo pageInfo, Example example) {
        pageInfo.setTotals(dao.countByPager(pageInfo, example));
        return dao.selectByPager(pageInfo, example);
    }

    protected abstract Example getPageExample(String fieldName, String keyword);
}

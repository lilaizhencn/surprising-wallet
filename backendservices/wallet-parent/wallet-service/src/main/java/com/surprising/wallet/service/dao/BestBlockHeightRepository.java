package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.data.CrudRepository;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;
import org.springframework.stereotype.Repository;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-05-02
 */
@Repository
@Deprecated
public interface BestBlockHeightRepository
        extends CrudRepository<BestBlockHeight, BestBlockHeightExample, Integer> {
}

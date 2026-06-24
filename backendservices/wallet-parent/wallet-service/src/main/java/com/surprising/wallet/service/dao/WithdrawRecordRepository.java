package com.surprising.wallet.service.dao;

import com.surprising.common.mybatis.sharding.data.CrudRepository;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import org.springframework.stereotype.Repository;

/**
 * 数据访问类
 *
 * @author atomex
 * @date 2018-04-02
 */
@Repository
@Deprecated
public interface WithdrawRecordRepository
        extends CrudRepository<WithdrawRecord, WithdrawRecordExample, Integer> {
}

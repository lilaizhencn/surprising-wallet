package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-04-02
 */
public interface WithdrawRecordService
        extends CrudService<WithdrawRecord, WithdrawRecordExample, Integer> {
}

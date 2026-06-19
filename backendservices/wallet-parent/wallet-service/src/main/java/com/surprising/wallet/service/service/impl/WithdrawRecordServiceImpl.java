package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.dao.WithdrawRecordRepository;
import com.surprising.wallet.service.service.WithdrawRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-04-02
 */
@Slf4j
@Service
public class WithdrawRecordServiceImpl
        extends AbstractCrudService<WithdrawRecordRepository, WithdrawRecord, WithdrawRecordExample, Integer>
        implements WithdrawRecordService {

    @Autowired
    private WithdrawRecordRepository withdrawRecordRepos;

    @Override
    protected WithdrawRecordExample getPageExample(String fieldName, String keyword) {
        WithdrawRecordExample example = new WithdrawRecordExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }
}
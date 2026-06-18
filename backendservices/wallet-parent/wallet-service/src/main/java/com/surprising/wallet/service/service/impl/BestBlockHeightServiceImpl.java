package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.service.AbstractCrudService;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;
import com.surprising.wallet.service.dao.BestBlockHeightRepository;
import com.surprising.wallet.service.service.BestBlockHeightService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-05-02
 */
@Slf4j
@Service
public class BestBlockHeightServiceImpl
        extends AbstractCrudService<BestBlockHeightRepository, BestBlockHeight, BestBlockHeightExample, Integer>
        implements BestBlockHeightService {

    @Autowired
    private BestBlockHeightRepository bestBlockHeightRepos;

    @Override
    protected BestBlockHeightExample getPageExample(String fieldName, String keyword) {
        BestBlockHeightExample example = new BestBlockHeightExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }
}
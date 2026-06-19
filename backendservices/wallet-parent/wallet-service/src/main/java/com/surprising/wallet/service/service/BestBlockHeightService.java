package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.service.CrudService;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-05-02
 */
public interface BestBlockHeightService
        extends CrudService<BestBlockHeight, BestBlockHeightExample, Integer> {
}

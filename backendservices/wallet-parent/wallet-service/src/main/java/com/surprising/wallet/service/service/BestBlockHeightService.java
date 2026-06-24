package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.service.CrudService;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;

/**
 * Legacy best_block_height checkpoint service. DB Asset Model scanners use
 * chain_scan_height; this service remains only for old-chain compatibility and
 * one-time migration seeding.
 *
 * @author lilaizhen
 * @date 2018-05-02
 */
@Deprecated
public interface BestBlockHeightService
        extends CrudService<BestBlockHeight, BestBlockHeightExample, Integer> {
}

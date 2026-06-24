package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-04-01
 */
public interface WithdrawTransactionService
        extends CrudService<WithdrawTransaction, WithdrawTransactionExample, Integer> {

    WithdrawTransaction getByTxId(String txid, RuntimeAsset currencyEnum);

}

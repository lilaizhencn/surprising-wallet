package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;

/**
 * Legacy withdraw_record adapter for external API and notification
 * compatibility. Runtime withdrawal execution is represented by
 * withdrawal_order/chain_signing_transaction and ledger_balance.
 *
 * @author lilaizhen
 * @date 2018-04-02
 */
@Deprecated
public interface WithdrawRecordService
        extends CrudService<WithdrawRecord, WithdrawRecordExample, Integer> {
}

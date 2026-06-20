package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;

import java.math.BigDecimal;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-03-31
 */
public interface UtxoTransactionService
        extends CrudService<UtxoTransaction, UtxoTransactionExample, Long> {
    /**
     * 获取utxo的balance总和
     *
     * @return
     */
    BigDecimal getTotalBalance(UtxoTransactionExample example, ShardTable table);

    int addOnDuplicateKey(UtxoTransaction tx, final ShardTable table);

    UtxoTransaction getByTxid(String txId, CurrencyEnum currencyEnum);

    int setSpentTxId(UtxoTransaction utxo, String spentTxid, CurrencyEnum currencyEnum);

    UtxoTransaction markAsSpent(UtxoTransactionExample example, CurrencyEnum currencyEnum);

    int markCredited(String txId, Short seq, CurrencyEnum currencyEnum);

}

package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.dao.UtxoTransactionRepository;
import com.surprising.wallet.service.service.UtxoTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-03-31
 */
@Slf4j
@Service
public class UtxoTransactionServiceImpl
        extends AbstractCrudService<UtxoTransactionRepository, UtxoTransaction, UtxoTransactionExample, Long>
        implements UtxoTransactionService {

    @Autowired
    private UtxoTransactionRepository utxoTransactionRepos;

    @Override
    protected UtxoTransactionExample getPageExample(String fieldName, String keyword) {
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }


    @Override
    public BigDecimal getTotalBalance(UtxoTransactionExample example, ShardTable table) {
        BigDecimal totalBalance = utxoTransactionRepos.getTotalBalance(example, table);
        return totalBalance;
    }

    @Override
    public UtxoTransaction getByTxid(String txId, CurrencyEnum currencyEnum) {
        ShardTable table = ShardTable.builder().prefix(currencyEnum.getName()).build();
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria().andTxIdEqualTo(txId);
        return getOneByExample(example, table).get();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UtxoTransaction markAsSpent(UtxoTransactionExample example, CurrencyEnum currencyEnum) {
        ShardTable table = ShardTable.builder().prefix(currencyEnum.getName()).build();

        UtxoTransaction utxoTransaction = getOneByExample(example, table).get();
        utxoTransaction.setStatus((byte) Constants.SIGNING);
        editById(utxoTransaction, table);
        return utxoTransaction;
    }


    @Override
    public int batchAddOnDuplicateKey(List<UtxoTransaction> records, ShardTable shardTable) {
        records.parallelStream().forEach((record) -> {
            addOnDuplicateKey(record, shardTable);
        });
        return records.size();
    }

    @Override
    public int addOnDuplicateKey(UtxoTransaction tx, ShardTable table) {
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria().andTxIdEqualTo(tx.getTxId()).andSeqEqualTo(tx.getSeq());
        Optional<UtxoTransaction> oneByExample = getOneByExample(example, table);
        if (!oneByExample.isPresent()) {
            return utxoTransactionRepos.insertOnDuplicateKey(tx, table);
        } else {
            UtxoTransaction exist = oneByExample.get();
            if (tx.getBlockHeight() > 0 && exist.getBlockHeight() <= 0) {
                exist.setBlockHeight(tx.getBlockHeight());
                exist.setUpdateDate(Date.from(Instant.now()));
                return editById(exist, table);

            } else {
                return 1;
            }
        }
    }

    @Override
    public int setSpentTxId(UtxoTransaction utxo, String spentTxid, CurrencyEnum currencyEnum) {
        ShardTable table = ShardTable.builder().prefix(currencyEnum.getName()).build();
        utxo.setSpentTxId(spentTxid);
        utxo.setStatus((byte) Constants.SENT);
        utxo.setUpdateDate(Date.from(Instant.now()));
        return editById(utxo, table);
    }


}
package com.surprising.wallet.service.wallet;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;
import com.surprising.wallet.service.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractWallet implements IWallet {


    @Autowired
    protected CurrencyBalanceService balanceService;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected WithdrawTransactionService withdrawTransactionService;
    @Autowired
    protected WithdrawRecordService recordService;

    @Autowired
    protected AccountTransactionService accountTransactionService;

    @Autowired
    protected UtxoTransactionService utxoTransactionService;
    @Autowired
    protected AddressService addressService;

    @Autowired
    protected TransactionService transactionService;

    protected void updateTotalCurrencyBalance(CurrencyEnum currency, BigDecimal balance) {
        CurrencyBalanceExample example = new CurrencyBalanceExample();
        example.createCriteria().andCurrencyIndexEqualTo(currency.getIndex());
        Optional<CurrencyBalance> oneByExample = balanceService.getOneByExample(example);
        CurrencyBalance currencyBalance;
        if (!oneByExample.isPresent()) {
            currencyBalance = CurrencyBalance.builder()
                    .currencyIndex(currency.getIndex())
                    .balance(balance)
                    .createDate(Date.from(Instant.now()))
                    .build();
            balanceService.add(currencyBalance);
        } else {
            currencyBalance = oneByExample.get();
            currencyBalance.setBalance(balance);
            currencyBalance.setUpdateDate(Date.from(Instant.now()));
            balanceService.editById(currencyBalance);
        }
    }

    /**
     * 更新差额
     *
     * @param currency
     * @param deltaBalance
     */
    protected void updateCurrencyDeltaBalance(CurrencyEnum currency, BigDecimal deltaBalance) {
        CurrencyBalanceExample example = new CurrencyBalanceExample();
        example.createCriteria().andCurrencyIndexEqualTo(currency.getIndex());
        Optional<CurrencyBalance> oneByExample = balanceService.getOneByExample(example);
        if (oneByExample.isPresent()) {
            CurrencyBalance currencyBalance = oneByExample.get();
            currencyBalance.setBalance(currencyBalance.getBalance().add(deltaBalance));
            currencyBalance.setUpdateDate(Date.from(Instant.now()));
            balanceService.editById(currencyBalance);
        }
    }

    /**
     * 更新钱包中的币余额
     */
    @Override
    public void updateTotalCurrencyBalance() {
        CurrencyEnum currency = getCurrency();
        log.info("update {} total Balance begin", currency.getName());

        if (shouldUpdateTotalBalance()) {
            BigDecimal balance = getBalance();
            updateTotalCurrencyBalance(currency, balance);
            log.info("update {} total Balance:{}", currency.getName(), balance);
        }

        log.info("update {} Balance end", currency.getName());
    }

    protected boolean shouldUpdateTotalBalance() {
        return true;
    }

    public TransactionDTO convertUtxoToDto(UtxoTransaction utxo) {
        StringBuilder txidBuilder = new StringBuilder();
        //utxo 的交易txid不能唯一标示一笔充值, 所以把`txid-seq`设置为唯一标识。方便统一处理
        txidBuilder.append(utxo.getTxId()).append("-").append(utxo.getSeq());
        return TransactionDTO.builder()
                .address(utxo.getAddress())
                .balance(utxo.getBalance())
                .blockHeight(utxo.getBlockHeight())
                .confirmNum(utxo.getConfirmNum())
                .txId(txidBuilder.toString())
                .currency(utxo.getCurrency())
                .biz(utxo.getBiz())
                .build();
    }

    public TransactionDTO convertAccountTxToDto(AccountTransaction accountTx) {
        return TransactionDTO.builder()
                .address(accountTx.getAddress())
                .balance(accountTx.getBalance())
                .blockHeight(accountTx.getBlockHeight())
                .confirmNum(accountTx.getConfirmNum())
                .txId(accountTx.getTxId())
                .biz(accountTx.getBiz())
                .currency((accountTx.getCurrency()))
                .build();
    }



    /**
     * 检测txid 是否是我们自己发出的交易，如果是，更新交易状态为已确认
     *
     * @param txId
     * @param currency
     */
    protected void updateWithdrawTXId(String txId, CurrencyEnum currency) {
        //先检测是不是我们发出的交易
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        WithdrawTransactionExample withExam = new WithdrawTransactionExample();
        withExam.createCriteria().andTxIdEqualTo(txId);
        Optional<WithdrawTransaction> oneByExample = withdrawTransactionService.getOneByExample(withExam, table);
        if (oneByExample.isPresent()) {
            WithdrawTransaction withdrawTransaction = oneByExample.get();
            withdrawTransaction.setStatus(Constants.CONFIRM);
            withdrawTransaction.setUpdateDate(Date.from(Instant.now()));
            withdrawTransactionService.editById(withdrawTransaction, table);

            WithdrawRecordExample recordExample = new WithdrawRecordExample();
            recordExample.createCriteria().andTxIdEqualTo(txId);
            List<WithdrawRecord> withdrawRecords = recordService.getByExample(recordExample, table);
            if (!CollectionUtils.isEmpty(withdrawRecords)) {
                withdrawRecords.parallelStream().forEach((record -> record.setStatus((byte) Constants.CONFIRM)));
                recordService.batchEdit(withdrawRecords, table);
            }
        }
    }

    public String getWithdrawAddress() {
        throw new RuntimeException(getCurrency().getName() + " does not support getWithdrawAddress method");
    }

    public void transfer(String address, CurrencyEnum currency, Date deadline) {
        throw new RuntimeException(getCurrency().getName() + " does not support transfer method");

    }

    @Override
    public String getTxId(WithdrawTransaction transaction) {
        throw new RuntimeException(getCurrency().getName() + " does not support getTxId method");
    }

    @Override
    public String getBlockHash(Long height) {

        throw new RuntimeException(getCurrency().getName() + " has not impl getBlockHash method");
    }

    //主要是用来启动内部事务
    protected <T> T getSelf(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

}

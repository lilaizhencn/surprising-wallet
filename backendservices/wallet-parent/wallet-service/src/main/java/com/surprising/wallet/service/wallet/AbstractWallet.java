package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.service.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractWallet implements IWallet {


    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected WithdrawTransactionService withdrawTransactionService;

    @Autowired
    protected AccountTransactionService accountTransactionService;

    @Autowired
    protected AddressService addressService;

    @Autowired
    @Lazy
    protected TransactionService transactionService;

    protected void updateTotalCurrencyBalance(RuntimeAsset currency, BigDecimal balance) {
        log.info("currency_balance write disabled; ledger_balance is runtime source currency={} balance={}",
                currency.getName(), balance);
    }

    /**
     * 更新差额
     *
     * @param currency
     * @param deltaBalance
     */
    protected void updateCurrencyDeltaBalance(RuntimeAsset currency, BigDecimal deltaBalance) {
        log.info("currency_balance delta write disabled; ledger_balance is runtime source currency={} delta={}",
                currency.getName(), deltaBalance);
    }

    /**
     * 更新钱包中的币余额
     */
    @Override
    public void updateTotalCurrencyBalance() {
        RuntimeAsset currency = getCurrency();
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
    protected void updateWithdrawTXId(String txId, RuntimeAsset currency) {
        log.info("legacy withdraw_record confirmation adapter disabled currency={} txId={}",
                currency.getName(), txId);
    }

    public String getWithdrawAddress() {
        throw new RuntimeException(getCurrency().getName() + " does not support getWithdrawAddress method");
    }

    public void transfer(String address, RuntimeAsset currency, Date deadline) {
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

package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author atomex
 */
@Slf4j
public abstract class AbstractAccountWallet extends AbstractWallet {

    @Override
    public Address genNewAddress(Long userId, Integer biz) {
        throw new RuntimeException(getClass().getName() + "{} does not support genNewAddress() method");
    }

    @Override
    public BigDecimal getBalance() {
        throw new RuntimeException(getClass().getName() + "{} does not support getBalance() method");
    }

    @Override
    public String sendRawTransaction(WithdrawTransaction transaction) {
        throw new RuntimeException(getClass().getName() + "{} does not support sendRawTransaction() method");
    }

    @Override
    public int getConfirm(String txId) {
        throw new RuntimeException(getClass().getName() + "{} does not support getConfirm() method");

    }

    @Override
    public boolean checkAddress(String addressStr) {
        throw new RuntimeException(getClass().getName() + "{} does not support checkAddress() method");
    }

    @Override
    public List<TransactionDTO> findRelatedTxs(Long height) {
        throw new RuntimeException(getClass().getName() + "{} does not support findRelatedTxs() method");

    }

    /**
     * 获取余额
     *
     * @param address
     * @param currency
     * @return
     */
    protected BigDecimal getBalance(String address, RuntimeAsset currency) {
        throw new RuntimeException(getClass().getName() + "{} does not support getBalance( String address,  RuntimeAsset currency) method");
    }


    /**
     * 构建交易
     *
     * @param record
     * @return
     */
    protected WithdrawTransaction buildTransaction(WithdrawRecord record) {
        throw new RuntimeException(getClass().getName() + "{} does not support buildTransaction method");

    }

    /**
     * 提现方法
     *
     * @param record
     * @return
     */
    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_UNCOMMITTED)
    public boolean withdraw(WithdrawRecord record) {
        throw new IllegalStateException(
                "legacy account withdraw runtime is disabled for currency " + record.getCurrency());
    }

    //当币种需要从多个地址划转到一个统一地址时，需要更新划转状态
    protected void updateLegacyAccountDepositStatus(String txId, RuntimeAsset currency) {
        log.info("legacy account deposit status update disabled currency={} txId={}",
                currency.getName(), txId);
    }


    protected boolean checkInternalTransferTx(String from, String txId) {
        log.info("legacy internal-transfer check disabled currency={} txId={}",
                getCurrency().getName(), txId);
        return false;
    }

    @Override
    public void updateTXConfirmation(RuntimeAsset currency) {
        log.info("legacy account deposit confirmation updater disabled currency={}",
                currency.getName());
    }

    /**
     * 提交前 计算交易hash
     */
    public static String caculateTransactionHash(String signedData) {
        //样例 https://ropsten.etherscan.io/tx/0xfd8acd10d72127f29f0a01d8bcaf0165665b5598781fe01ca4bceaa6ab9f2cb0
        String txHash = Hash.sha3(signedData);
        return txHash;
    }
}

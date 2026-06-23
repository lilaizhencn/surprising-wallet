package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author atomex
 * @data 27/03/2018
 */

public interface IWallet {

    /**
     * 获得当前币种
     *
     * @return
     */
    CurrencyEnum getCurrency();

    /**
     * Runtime deposit threshold. New chains override this from chain_profile;
     * the enum value remains the legacy compatibility fallback.
     */
    default long getDepositConfirmationThreshold() {
        return getCurrency().getDepositConfirmNum();
    }

    /**
     * Runtime dust threshold in the chain's smallest unit. New chains should
     * override this from chain_profile; zero means no generic threshold.
     */
    default long getDustThresholdAtomic() {
        return 0L;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    BigDecimal getDecimal();

    /**
     * 生成新地址
     *
     * @param userId 用户id
     * @param biz    业务类型： spot、c2c 等
     * @return
     */
    Address genNewAddress(Long userId, Integer biz);

    /**
     * 返回当前币种钱包中的余额
     *
     * @return
     */
    BigDecimal getBalance();

    /**
     * 更新币总余额
     */
    void updateTotalCurrencyBalance();


    /**
     * 获得区块链的当前最大高度
     *
     * @return
     */
    Long getBestHeight();


    /**
     * 提现方法
     *
     * @param record
     * @return
     */

    boolean withdraw(WithdrawRecord record);


    /**
     * 发送签好的原始交易
     *
     * @param transaction
     * @return
     */
    String sendRawTransaction(WithdrawTransaction transaction);

    /**
     * 获得区块hash
     *
     * @param height
     * @return
     */
    String getBlockHash(Long height);

    /**
     * 检查地址格式是否正确
     *
     * @param address
     * @return
     */
    boolean checkAddress(String address);


    /**
     * 获取交易的确认数
     *
     * @param txId
     * @return
     */
    int getConfirm(String txId);

    /**
     * 获取当前区块高度下的充值交易
     *
     * @param height
     * @return
     */
    List<TransactionDTO> findRelatedTxs(Long height);

    /**
     * 获取txid
     *
     * @param transaction
     * @return
     */
    String getTxId(WithdrawTransaction transaction);


    /**
     * 更新已经扫描到的交易的确认数
     *
     * @param currency
     */
    void updateTXConfirmation(CurrencyEnum currency);


}

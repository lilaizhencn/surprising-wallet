package com.surprising.wallet.service.wallet;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.web3j.crypto.Hash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
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
    protected BigDecimal getBalance(String address, CurrencyEnum currency) {
        throw new RuntimeException(getClass().getName() + "{} does not support getBalance( String address,  CurrencyEnum currency) method");
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
        CurrencyEnum currency = CurrencyEnum.parseValue(record.getCurrency());
        BigDecimal balance = getBalance(getWithdrawAddress(), currency);
        if (balance.compareTo(record.getBalance()) < 0) {
            log.error("The {} 钱包余额不足", currency.getName());
            return false;
        }
        WithdrawTransaction transaction = buildTransaction(record);
        if (transaction == null) {
            log.error(" {} 提现失败, 构建交易对象失败 {}", currency.getName(), transaction);
            return false;
        }
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        String transactionId = transaction.getId().toString();

        record.setStatus((byte) Constants.SIGNING);
        record.setTxId(transactionId);
        record.setUpdateDate(Date.from(Instant.now()));
        recordService.editById(record, table);
        //把交易推送到待签名队列
        String val = JSONObject.toJSONString(transaction);
        //只支持单签，所以直接用第二台机器签名
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_SECOND_KEY, val);
        log.info("{} 交易构建完成, id:{}", currency.getName(), transaction.getId());
        return true;
    }

    //当币种需要从多个地址划转到一个统一地址时，需要更新划转状态
    protected void updateAccountTransaction(String txId, CurrencyEnum currency) {
        try {
            ShardTable table = ShardTable.builder().prefix(currency.getName()).build();

            WithdrawTransaction withdrawTransaction = withdrawTransactionService.getByTxId(txId, currency);
            if (!ObjectUtils.isEmpty(withdrawTransaction)) {
                JSONObject sigJson = JSONObject.parseObject(withdrawTransaction.getSignature());
                String fromAddr = sigJson.getString("from");
                AccountTransactionExample example = new AccountTransactionExample();
                example.createCriteria().andAddressEqualTo(fromAddr).andStatusEqualTo((byte) Constants.SIGNING);
                AccountTransaction transaction = new AccountTransaction();
                transaction.setStatus((byte) Constants.CONFIRM);
                accountTransactionService.editByExample(transaction, example, table);
            }
        } catch (Throwable e) {
            log.error("updateWithdrawTXId error ,txid:{}", txId);
        }

    }


    protected boolean checkInternalTransferTx(String from, String txId) {
        if (StringUtils.hasText(from) && from.equals(getWithdrawAddress())) {
            WithdrawRecordExample recordExample = new WithdrawRecordExample();
            recordExample.createCriteria().andTxIdEqualTo(txId);
            ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
            List<WithdrawRecord> withdrawRecords = recordService.getByExample(recordExample, table);
            //如果发送者是内部的提现地址，但是找不到提现记录 @WithdrawRecord, 说明是往内转转账（比如往erc20地址上转0.03eth），不是用户充值
            if (CollectionUtils.isEmpty(withdrawRecords)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updateTXConfirmation(CurrencyEnum currency) {
        log.info("更新 {} 交易确认数 开始", currency.getName());
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        PageInfo page = new PageInfo();
        int size = 500;
        page.setPageSize(size);
        page.setSortItem("id");
        page.setSortType(PageInfo.SORT_TYPE_ASC);
        page.setStartIndex(0);

        AccountTransactionExample example = new AccountTransactionExample();
        example.createCriteria().andConfirmNumLessThan(currency.getConfirmNum());
        while (true) {
            List<AccountTransaction> acTxs = accountTransactionService.getByPage(page, example, table);
            acTxs.parallelStream().forEach((tx) -> {
                tx.setCurrency(currency.getIndex());
                tx.setUpdateDate(Date.from(Instant.now()));

                int confirm = getConfirm(tx.getTxId());
                tx.setConfirmNum(confirm > 0 ? confirm : 0L);
                accountTransactionService.editById(tx, table);
                TransactionDTO dto = convertAccountTxToDto(tx);
                transactionService.saveTransaction(dto);

            });

            if (acTxs.size() < size) {
                break;
            }
            page.setStartIndex(page.getStartIndex() + size);
        }

        log.info("更新 {} 交易确认数 结束", currency.getName());
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

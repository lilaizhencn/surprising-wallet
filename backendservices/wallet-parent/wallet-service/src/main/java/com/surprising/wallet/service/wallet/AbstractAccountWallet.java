package com.surprising.wallet.service.wallet;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
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
                "legacy account withdraw_record runtime is disabled for currency " + record.getCurrency());
    }

    //当币种需要从多个地址划转到一个统一地址时，需要更新划转状态
    protected void updateAccountTransaction(String txId, RuntimeAsset currency) {
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
        log.info("legacy withdraw_record internal-transfer check disabled currency={} txId={}",
                getCurrency().getName(), txId);
        return false;
    }

    @Override
    public void updateTXConfirmation(RuntimeAsset currency) {
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

package com.surprising.wallet.service.service;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.surprising.wallet.common.utils.Constants.WALLET_DEPOSIT_KEY;

/**
 * @author lilaizhen
 */
@Slf4j
@Component
public class TransactionService {
    @Autowired
    UtxoTransactionService utxoService;

    @Autowired
    AccountTransactionService accountTransactionService;

    @Autowired
    WithdrawRecordService withdrawRecordService;

    @Autowired
    WalletContext walletContext;

    @Autowired
    WithdrawTransactionService transactionService;

    /**
     * 充值，把充值交易推送到各自的业务线队列
     */
    public void saveTransaction(List<TransactionDTO> dtos) {
        log.info("saveTransactions dto begin");
        dtos.parallelStream().forEach(this::saveTransaction);
        log.info("saveTransactions dto end");
    }

    public void saveTransaction(TransactionDTO dto) {
        // INTERNAL 一般代表是内部找零地址的默认业务线，不需要通知到账
        if (BizEnum.INTERNAL.getIndex() == dto.getBiz()) {
            log.warn("internal类型的转账不需要通知 交易id:{}", dto.getTxId());
            return;
        }
        log.info("saveTransaction dto: {} begin", dto.getTxId());
        // String depositKey = WALLET_DEPOSIT_KEY + dto.getBiz();
        String depositKey = WALLET_DEPOSIT_KEY;
        String val = JSONObject.toJSONString(dto);
        REDIS.rPush(depositKey, val);
        log.info("saveTransaction dto: {} end", dto.getTxId());
    }


    /**
     * 把提现记录入库，账户类型的币直接发出提现请求，但是utxo类型的币在 {@link com.surprising.wallet.jobs.withdraw}中批量汇出
     */
    @Transactional(rollbackFor = {Throwable.class}, isolation = Isolation.READ_UNCOMMITTED)
    public boolean withdraw(WithdrawRecord record) {
        log.info("提现操作 开始 提现id:{}", record.getWithdrawId());

        CurrencyEnum currency = CurrencyEnum.parseValue(record.getCurrency());
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        //在交易未构建成功之前，用withdrawId占位，然后入库
        record.setTxId(record.getWithdrawId());
        record.setStatus((byte) Constants.WAITING);
        withdrawRecordService.add(record, table);
        WithdrawRecordExample example = new WithdrawRecordExample();
        example.createCriteria().andWithdrawIdEqualTo(record.getWithdrawId());
        List<WithdrawRecord> exist = withdrawRecordService.getByExample(example, table);
        if (exist.size() > 1) {
            withdrawRecordService.removeById(record.getId(), table);
            return true;
        }
        //获取币种钱包bean,通过对应的bean的withdraw方法提现
        IWallet wallet = walletContext.getWallet(currency);
        boolean success = wallet.withdraw(record);
        if (!success) {
            log.error("提现操作失败 删除提现记录 {}", record.getId());
            withdrawRecordService.removeById(record.getId(), table);
        }

        log.info("提现操作 结束 提现id:{}", record.getWithdrawId());
        return success;
    }

    /**
     * 把已经签好的交易发送出去
     * 更新withdrawrecord表和utxo表中的txid
     * 把数据推送到 {@Link Constants.WALLET_WITHDRAW_TX_BIZ_KEY}各个业务线的队列，回写txid
     *
     * @param transaction 已经签好的交易
     */
    public void sendWithdrawTransaction(WithdrawTransaction transaction) {
        log.info("广播签名后的交易 开始 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());

        //签名是否成功
        if (!signature.containsKey("valid") || !signature.getBoolean("valid")) {
            log.error("广播签名后的交易 签名失败 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
            return;
        }

        CurrencyEnum currency = CurrencyEnum.parseValue(transaction.getCurrency());
        IWallet wallet = walletContext.getWallet(currency);

        String txId = wallet.sendRawTransaction(transaction);

        if (!StringUtils.hasText(txId)) {
            log.error("广播签名后的交易 广播失败 币种id:{} 提现信息:{}", currency.getName(), transaction);
            return;
        }

        // String txId = wallet.getTxId(transaction);
        transaction.setTxId(txId);
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();

        //2 代表交易已发送
        short status = Constants.SENT;
        transaction.setStatus(status);
        transaction.setUpdateDate(Date.from(Instant.now()));
        transactionService.editById(transaction, table);

        //更新withdrawrecord表中的txid
        WithdrawRecordExample example = new WithdrawRecordExample();
        example.createCriteria().andTxIdEqualTo(transaction.getId().toString());
        List<WithdrawRecord> records = withdrawRecordService.getByExample(example, table);
        records.parallelStream().forEach((record) -> {
            record.setTxId(txId);
            record.setStatus((byte) status);
            record.setUpdateDate(Date.from(Instant.now()));
            withdrawRecordService.editById(record, table);
            // String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY + record.getBiz();
            String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY;
            String val = JSONObject.toJSONString(record);
            REDIS.lPush(key, val);
        });
        log.info("广播签名后的交易 成功 更新数据库完成 币种id:{} 交易id:{}", currency.getName(), transaction.getTxId());
    }
}

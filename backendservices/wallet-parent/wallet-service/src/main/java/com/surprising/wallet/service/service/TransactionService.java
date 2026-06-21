package com.surprising.wallet.service.service;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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
    AddressService addressService;

    @Autowired
    UserAssetService userAssetService;

    @Autowired
    AccountTransactionService accountTransactionService;

    @Autowired
    WithdrawRecordService withdrawRecordService;

    @Autowired
    WalletContext walletContext;

    @Autowired
    WithdrawTransactionService transactionService;

    @Autowired
    ChainJdbcRepository chainJdbcRepository;

    /**
     * 充值，把充值交易推送到各自的业务线队列
     */
    public void saveTransaction(List<TransactionDTO> dtos) {
        log.info("saveTransactions dto begin");
        dtos.forEach(this::saveTransaction);
        log.info("saveTransactions dto end");
    }

    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public void saveTransaction(TransactionDTO dto) {
        // INTERNAL 一般代表是内部找零地址的默认业务线，不需要通知到账
        if (BizEnum.INTERNAL.getIndex() == dto.getBiz()) {
            log.warn("internal类型的转账不需要通知 交易id:{}", dto.getTxId());
            return;
        }
        log.info("saveTransaction dto: {} begin", dto.getTxId());
        CurrencyEnum currency = CurrencyEnum.parseValue(dto.getCurrency());
        UtxoKey utxoKey = UtxoKey.parse(dto.getTxId());
        if (currency == CurrencyEnum.LTC
                && utxoKey != null
                && isKnownWalletTransaction(utxoKey.txId, currency)) {
            log.info("skip LTC self-transfer deposit event for tx={}", utxoKey.txId);
            return;
        }
        if (dto.getConfirmNum() != null && dto.getConfirmNum() >= currency.getDepositConfirmNum()) {
            creditDepositIfNeeded(dto, currency);
        }
        // String depositKey = WALLET_DEPOSIT_KEY + dto.getBiz();
        String depositKey = WALLET_DEPOSIT_KEY;
        String val = JSONObject.toJSONString(dto);
        REDIS.rPush(depositKey, val);
        log.info("saveTransaction dto: {} end", dto.getTxId());
    }


    /**
     * 把提现记录入库，账户类型的币直接发出提现请求，但是utxo类型的币在 {@link com.surprising.wallet.jobs.withdraw}中批量汇出
     */
    @Transactional(rollbackFor = {Throwable.class}, isolation = Isolation.READ_COMMITTED)
    public boolean withdraw(WithdrawRecord record) {
        log.info("提现操作 开始 提现id:{}", record.getWithdrawId());

        CurrencyEnum currency = CurrencyEnum.parseValue(record.getCurrency());
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        WithdrawRecordExample duplicateExample = new WithdrawRecordExample();
        duplicateExample.createCriteria().andWithdrawIdEqualTo(record.getWithdrawId());
        if (!withdrawRecordService.getByExample(duplicateExample, table).isEmpty()) {
            log.info("提现操作 重复提现id已存在:{}", record.getWithdrawId());
            return true;
        }
        if (currency == CurrencyEnum.LTC) {
            chainJdbcRepository.createWithdrawalOrder(
                    record.getWithdrawId(),
                    record.getUserId(),
                    "LTC",
                    "LTC",
                    record.getAddress(),
                    record.getBalance(),
                    record.getFee() == null ? BigDecimal.ZERO : record.getFee());
        }
        BigDecimal frozenAmount = withdrawFrozenAmount(record);
        if (!userAssetService.freeze(record.getUserId(), currency.getIndex(), frozenAmount)) {
            log.warn("提现操作 用户余额不足 userId:{} currency:{} amount:{}",
                    record.getUserId(), currency.getName(), frozenAmount);
            return false;
        }
        if (currency == CurrencyEnum.LTC
                && !chainJdbcRepository.freezeLedgerBalance(
                "LTC", "LTC", record.getUserId().toString(), frozenAmount)) {
            userAssetService.unfreeze(record.getUserId(), currency.getIndex(), frozenAmount);
            chainJdbcRepository.updateWithdrawalStatus(
                    "LTC", record.getWithdrawId(), "FAILED", null, null, "ledger freeze failed");
            return false;
        }
        if (currency == CurrencyEnum.LTC) {
            chainJdbcRepository.updateWithdrawalStatus(
                    "LTC", record.getWithdrawId(), "FROZEN", null, null, null);
        }
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
            userAssetService.unfreeze(record.getUserId(), currency.getIndex(), frozenAmount);
            if (currency == CurrencyEnum.LTC) {
                chainJdbcRepository.releaseLockedBalance(
                        "LTC", "LTC", record.getUserId().toString(), frozenAmount);
                chainJdbcRepository.updateWithdrawalStatus(
                        "LTC", record.getWithdrawId(), "FAILED", null, null, "wallet rejected withdrawal");
            }
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
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public boolean sendWithdrawTransaction(WithdrawTransaction transaction) {
        log.info("广播签名后的交易 开始 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        CurrencyEnum currency = CurrencyEnum.parseValue(transaction.getCurrency());
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();

        //签名是否成功
        if (!signature.containsKey("valid") || !signature.getBoolean("valid")) {
            log.error("广播签名后的交易 签名失败 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
            if (currency == CurrencyEnum.LTC) {
                failLitecoinTransaction(transaction, signature.getString("error"));
            }
            return true;
        }

        var persisted = transactionService.getById(transaction.getId(), table);
        if (persisted.isPresent()
                && persisted.get().getStatus() != null
                && persisted.get().getStatus() >= Constants.SENT
                && StringUtils.hasText(persisted.get().getTxId())
                && !"singing".equalsIgnoreCase(persisted.get().getTxId())
                && !"signing".equalsIgnoreCase(persisted.get().getTxId())) {
            log.info("广播签名后的交易 已完成幂等跳过 币种:{} 交易id:{} txid:{}",
                    currency.getName(), transaction.getId(), persisted.get().getTxId());
            return true;
        }
        IWallet wallet = walletContext.getWallet(currency);

        String txId = wallet.sendRawTransaction(transaction);

        if (!StringUtils.hasText(txId)) {
            log.error("广播签名后的交易 广播失败 币种id:{} 提现信息:{}", currency.getName(), transaction);
            if (currency == CurrencyEnum.LTC) {
                markLitecoinRetrying(transaction, signature, "broadcast failed");
            }
            return false;
        }

        // String txId = wallet.getTxId(transaction);
        transaction.setTxId(txId);
        //2 代表交易已发送
        short status = Constants.SENT;
        transaction.setStatus(status);
        transaction.setUpdateDate(Date.from(Instant.now()));
        transactionService.editById(transaction, table);

        //更新withdrawrecord表中的txid
        WithdrawRecordExample example = new WithdrawRecordExample();
        example.createCriteria().andTxIdEqualTo(transaction.getId().toString());
        List<WithdrawRecord> records = withdrawRecordService.getByExample(example, table);
        records.forEach((record) -> {
            record.setTxId(txId);
            record.setStatus((byte) status);
            record.setUpdateDate(Date.from(Instant.now()));
            withdrawRecordService.editById(record, table);
            if (currency != CurrencyEnum.LTC) {
                userAssetService.deduct(record.getUserId(), currency.getIndex(), withdrawFrozenAmount(record));
            } else {
                chainJdbcRepository.updateWithdrawalStatus(
                        "LTC", record.getWithdrawId(), "SENT", null, txId, null);
            }
            // String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY + record.getBiz();
            String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY;
            String val = JSONObject.toJSONString(record);
            REDIS.lPush(key, val);
        });
        if (currency == CurrencyEnum.LTC && "collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    "LTC",
                    signature.getString("collectionId"),
                    "SENT",
                    txId,
                    null,
                    transaction.getSignature());
        }
        log.info("广播签名后的交易 成功 更新数据库完成 币种id:{} 交易id:{}", currency.getName(), transaction.getTxId());
        return true;
    }

    private void markLitecoinRetrying(WithdrawTransaction transaction, JSONObject signature, String error) {
        if ("collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    "LTC", signature.getString("collectionId"), "RETRYING", null, error,
                    transaction.getSignature());
            return;
        }
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> chainJdbcRepository.updateWithdrawalStatus(
                "LTC", record.getWithdrawId(), "RETRYING", null, null, error));
    }

    private void failLitecoinTransaction(WithdrawTransaction transaction, String error) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String lockRef = transaction.getId().toString();
        ShardTable table = ShardTable.builder().prefix(CurrencyEnum.LTC.getName()).build();
        UtxoTransactionExample utxoExample = new UtxoTransactionExample();
        utxoExample.createCriteria().andSpentTxIdEqualTo(lockRef);
        List<com.surprising.wallet.common.pojo.UtxoTransaction> utxos =
                utxoService.getByExample(utxoExample, table);
        utxos.forEach(utxo -> {
            utxo.setSpent((byte) 0);
            utxo.setSpentTxId(Constants.UNSPENT_TX_ID);
            utxo.setStatus((byte) Constants.WAITING);
            utxo.setUpdateDate(Date.from(Instant.now()));
        });
        if (!utxos.isEmpty()) {
            utxoService.batchEdit(utxos, table);
        }
        chainJdbcRepository.releaseUtxos("LTC", lockRef);

        if ("collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    "LTC", signature.getString("collectionId"), "FAILED", null, error,
                    transaction.getSignature());
            return;
        }
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> {
            BigDecimal amount = withdrawFrozenAmount(record);
            userAssetService.unfreeze(record.getUserId(), CurrencyEnum.LTC.getIndex(), amount);
            chainJdbcRepository.releaseLockedBalance(
                    "LTC", "LTC", record.getUserId().toString(), amount);
            chainJdbcRepository.updateWithdrawalStatus(
                    "LTC", record.getWithdrawId(), "FAILED", null, null, error);
            record.setStatus((byte) Constants.DELETE);
            record.setUpdateDate(Date.from(Instant.now()));
            withdrawRecordService.editById(record, table);
        });
    }

    private void creditDepositIfNeeded(TransactionDTO dto, CurrencyEnum currency) {
        UtxoKey utxoKey = UtxoKey.parse(dto.getTxId());
        if (utxoKey == null) {
            return;
        }
        Address address = addressService.getAddress(dto.getAddress(), currency);
        if (address == null || address.getUserId() == null || address.getUserId() <= 0) {
            return;
        }
        if (currency == CurrencyEnum.LTC) {
            DepositEvent event = new DepositEvent(
                    ChainType.LTC,
                    "LTC",
                    utxoKey.txId,
                    null,
                    dto.getAddress(),
                    dto.getBalance(),
                    dto.getBlockHeight(),
                    dto.getConfirmNum().intValue(),
                    null,
                    null);
            chainJdbcRepository.recordAndCreditDeposit(
                    event,
                    utxoKey.seq,
                    (int) currency.getDepositConfirmNum(),
                    address.getUserId().toString());
            if (dto.getConfirmNum() >= currency.getDepositConfirmNum()) {
                chainJdbcRepository.markUtxoCredited("LTC", utxoKey.txId, utxoKey.seq);
            }
        }
        int marked = utxoService.markCredited(utxoKey.txId, utxoKey.seq, currency);
        if (marked > 0) {
            userAssetService.addBalance(address.getUserId(), currency.getIndex(), dto.getBalance());
            log.info("充值入账成功 userId:{} currency:{} tx:{} amount:{}",
                    address.getUserId(), currency.getName(), dto.getTxId(), dto.getBalance());
        }
    }

    private boolean isKnownWalletTransaction(String txId, CurrencyEnum currency) {
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        com.surprising.wallet.service.criteria.WithdrawTransactionExample example =
                new com.surprising.wallet.service.criteria.WithdrawTransactionExample();
        example.createCriteria().andTxIdEqualTo(txId);
        return transactionService.getOneByExample(example, table).isPresent();
    }

    private BigDecimal withdrawFrozenAmount(WithdrawRecord record) {
        BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
        return record.getBalance().add(fee);
    }

    private static class UtxoKey {
        private final String txId;
        private final Short seq;

        private UtxoKey(String txId, Short seq) {
            this.txId = txId;
            this.seq = seq;
        }

        private static UtxoKey parse(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            int split = value.lastIndexOf('-');
            if (split <= 0 || split == value.length() - 1) {
                return null;
            }
            try {
                return new UtxoKey(value.substring(0, split), Short.parseShort(value.substring(split + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}

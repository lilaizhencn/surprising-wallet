package com.surprising.wallet.jobs.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Sets;
import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.service.WithdrawRecordService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.surprising.wallet.common.currency.CurrencyEnum.*;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractBatchWithdrawJob {
    //一次处理10笔交易
    private final int COUNT = 10;
    public CurrencyEnum currency;
    @Autowired
    WithdrawRecordService recordService;
    @Autowired
    UtxoTransactionService utxoService;
    @Autowired
    AddressService addressService;
    @Autowired
    WalletContext walletContext;
    @Autowired
    WithdrawTransactionService transactionService;
    @Autowired
    ChainJdbcRepository chainJdbcRepository;

    private static final Set<CurrencyEnum> SINGLE_SIG_CURRENCY = Collections.emptySet();
    private static final int DEFAULT_FEE_RATE = 10;

    public void execute() {
        log.info("提现任务开始 币种:{}", currency.getName());

        try {
            ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
            WithdrawRecordExample example = new WithdrawRecordExample();
            example.createCriteria().andStatusEqualTo((byte) Constants.WAITING);
            PageInfo pageInfo = new PageInfo();
            pageInfo.setPageSize(COUNT);
            pageInfo.setStartIndex(0);
            pageInfo.setSortItem("id");
            pageInfo.setSortType(PageInfo.SORT_TYPE_ASC);

            while (true) {
                List<WithdrawRecord> records = recordService.getByPage(pageInfo, example, table);
                if (CollectionUtils.isEmpty(records)) {
                    break;
                }
                WithdrawTransaction transaction = buildTransaction(records);
                if (transaction == null) {
                    log.error("提现任务异常 交易创建失败 币种:{}", currency.getName());
                    break;
                }
                //把交易推送到待签名队列
                String val = JSONObject.toJSONString(transaction);

                if (SINGLE_SIG_CURRENCY.contains(currency)) {
                    REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_SECOND_KEY, val);
                    log.info("交易推送到第二次签名服务{}", transaction.getId());
                } else {
                    REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, val);
                    log.info("交易推送到第一次签名服务{}", transaction.getId());
                }
                log.info("构建交易成功 id:{}", transaction.getId());

                //说明数据库中没有等待签名的交易了
                if (records.size() < COUNT) {
                    break;
                }
            }
        } catch (Throwable e) {
            log.error("提现任务扫描数据,构建交易,发送到redis队列出现异常 币种id:{}", currency.getName(), e);
        }


        log.info("提现任务结束 币种:{}", currency.getName());

    }

    protected WithdrawTransaction buildTransaction(List<WithdrawRecord> records) {
        log.info("构建提现交易对象开始");
        int size = 1;
        WithdrawTransaction transaction = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal withdrawAmount = BigDecimal.ZERO;
        for (WithdrawRecord record : records) {
            totalAmount = totalAmount.add(record.getBalance()).add(record.getFee());
            withdrawAmount = withdrawAmount.add(record.getBalance());
        }
        Integer redisFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        int feeRate = redisFeeRate == null || redisFeeRate <= 0 ? defaultFeeRate() : redisFeeRate;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria().andStatusEqualTo((byte) Constants.WAITING).andConfirmNumGreaterThanOrEqualTo(currency.getDepositConfirmNum());
        PageInfo pageInfo = new PageInfo();
        pageInfo.setPageSize(size);
        pageInfo.setStartIndex(0);
        pageInfo.setSortItem("id");
        pageInfo.setSortType(PageInfo.SORT_TYPE_ASC);

        //选取utxo
        LinkedList<UtxoTransaction> utxos = new LinkedList<>();
        BigDecimal walletAmount = BigDecimal.ZERO;
        while (true) {
            List<UtxoTransaction> tmps = utxoService.getByPage(pageInfo, example, table);
            if (CollectionUtils.isEmpty(tmps)) {
                log.error("构建交易失败 钱包余额不足");
                return null;
            }
            utxos.addAll(tmps);
            //因为page size 为 1，所以查询结果中只有一条数据
            UtxoTransaction utxo = tmps.get(0);
            walletAmount = walletAmount.add(utxo.getBalance());
            if (walletAmount.compareTo(requiredAmount(totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate)) > 0) {
                break;
            }
            pageInfo.setStartIndex(pageInfo.getStartIndex() + size);
        }
        //反向过滤一遍，或许后续加入的utxo金额较大，能减少使用的utxo数量
        Iterator<UtxoTransaction> descendingIterator = utxos.descendingIterator();
        List<Address> addresses = new LinkedList<>();
        utxos = new LinkedList<>();
        walletAmount = BigDecimal.ZERO;

        while (descendingIterator.hasNext()) {
            UtxoTransaction utxo = descendingIterator.next();
            utxos.add(utxo);
            walletAmount = walletAmount.add(utxo.getBalance());
            Address address = addressService.getAddress(utxo.getAddress(), table);
            addresses.add(address);
            if (walletAmount.compareTo(requiredAmount(totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate)) > 0) {
                break;
            }
        }

        //初始化交易
        JSONObject signature = new JSONObject();
        IWallet wallet = walletContext.getWallet(currency);
        Address changeAddress = wallet.genNewAddress(Constants.USER_ID, Constants.BIZ);
        signature.put("utxos", utxos);
        signature.put("addresses", addresses);
        signature.put("withdraw", records);
        signature.put("changeAddress", changeAddress.getAddress());
        signature.put("feeRate", feeRate);
        signature.put("totalAmount", totalAmount.toPlainString());

        transaction = WithdrawTransaction.builder()
                .balance(walletAmount)
                .currency(currency.getIndex())
                .status(Constants.SIGNING)
                .txId("singing")
                .signature(signature.toJSONString())
                .build();
        transactionService.add(transaction, table);

        String transactionId = transaction.getId().toString();

        if (currency == LTC || currency == DOGE) {
            String chain = currency.getName().toUpperCase(Locale.ROOT);
            for (UtxoTransaction utxo : utxos) {
                int locked = chainJdbcRepository.lockUtxo(
                        chain, utxo.getTxId(), utxo.getSeq(), transactionId);
                if (locked != 1) {
                    throw new IllegalStateException(
                            "failed to lock unified " + chain + " UTXO "
                                    + utxo.getTxId() + ":" + utxo.getSeq());
                }
            }
            String fromAddress = addresses.isEmpty() ? null : addresses.get(0).getAddress();
            records.forEach(record -> {
                chainJdbcRepository.updateWithdrawalStatus(
                        chain, record.getWithdrawId(), "UTXO_LOCKED", fromAddress, null, null);
                chainJdbcRepository.updateWithdrawalStatus(
                        chain, record.getWithdrawId(), "SIGNING", fromAddress, null, null);
            });
        }

        //更新utxo和WithdrawRecord的status
        List<UtxoTransaction> spends = utxos.parallelStream().map((utxo) -> UtxoTransaction.builder()
                .id(utxo.getId())
                .spentTxId(transactionId)
                .spent((byte) 1)
                .status((byte) Constants.SIGNING)
                .updateDate(Date.from(Instant.now()))
                .build()).collect(Collectors.toList());
        utxoService.batchEdit(spends, table);

        records.parallelStream().forEach((record) -> {
            record.setStatus((byte) Constants.SIGNING);
            record.setTxId(transactionId);
            record.setUpdateDate(Date.from(Instant.now()));
        });
        recordService.batchEdit(records, table);


        log.info("交易创建完成");
        return transaction;

    }

    private BigDecimal requiredAmount(BigDecimal userFeeRequired, BigDecimal withdrawAmount,
                                      int inputCount, int outputCount, int feeRate) {
        long feeSat = estimateNetworkFeeAtomic(Math.max(inputCount, 1), outputCount + 1, feeRate);
        BigDecimal networkFee = BigDecimal.valueOf(feeSat).divide(currency.getDecimal());
        BigDecimal dynamicRequired = withdrawAmount.add(networkFee);
        return dynamicRequired.max(userFeeRequired);
    }

    protected int defaultFeeRate() {
        return DEFAULT_FEE_RATE;
    }

    protected long estimateNetworkFeeAtomic(int inputCount, int outputCount, int feeRate) {
        return P2wshFeeCalculator.calculateFeeSat(inputCount, outputCount, feeRate);
    }
}

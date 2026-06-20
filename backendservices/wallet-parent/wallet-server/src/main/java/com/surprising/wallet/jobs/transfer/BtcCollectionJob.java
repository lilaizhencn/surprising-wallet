package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
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
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.surprising.wallet.common.utils.Constants.UNSPENT_TX_ID;

@Slf4j
@Component
public class BtcCollectionJob {
    private static final int PAGE_SIZE = 10;
    private static final int DEFAULT_FEE_RATE = 10;

    private final AddressService addressService;
    private final UtxoTransactionService utxoService;
    private final WithdrawTransactionService transactionService;

    @Value("${atomex.wallet.collection.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.hot.user-id:0}")
    private Long hotUserId;

    @Value("${atomex.wallet.hot.biz:0}")
    private Integer hotBiz;

    @Value("${atomex.wallet.hot.address-index:0}")
    private Integer hotAddressIndex;

    public BtcCollectionJob(AddressService addressService, UtxoTransactionService utxoService,
                            WithdrawTransactionService transactionService) {
        this.addressService = addressService;
        this.utxoService = utxoService;
        this.transactionService = transactionService;
    }

    @Scheduled(cron = "15/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        CurrencyEnum currency = CurrencyEnum.BTC;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        Address hotAddress = getHotAddress(table);
        if (hotAddress == null) {
            log.warn("BTC归集跳过: 未找到热提地址 userId={} biz={} index={}", hotUserId, hotBiz, hotAddressIndex);
            return;
        }

        List<UtxoTransaction> utxos = findCollectableUtxos(table, currency);
        if (CollectionUtils.isEmpty(utxos)) {
            return;
        }

        List<Address> inputAddresses = new ArrayList<>();
        BigDecimal inputAmount = BigDecimal.ZERO;
        for (UtxoTransaction utxo : utxos) {
            Address address = addressService.getAddress(utxo.getAddress(), table);
            if (address == null || address.getUserId() == null || address.getUserId() <= 0) {
                continue;
            }
            inputAddresses.add(address);
            inputAmount = inputAmount.add(utxo.getBalance());
        }
        if (CollectionUtils.isEmpty(inputAddresses)) {
            return;
        }

        int feeRate = getFeeRate(currency);
        long inputSat = inputAmount.multiply(currency.getDecimal()).longValue();
        long feeSat = P2wshFeeCalculator.calculateFeeSat(inputAddresses.size(), 1, feeRate);
        long outputSat = inputSat - feeSat;
        if (outputSat < P2wshFeeCalculator.DUST_THRESHOLD_SAT) {
            log.warn("BTC归集跳过: amount={} sat fee={} sat output dust", inputSat, feeSat);
            return;
        }

        Date now = Date.from(Instant.now());
        BigDecimal outputAmount = BigDecimal.valueOf(outputSat).divide(currency.getDecimal());
        BigDecimal feeAmount = BigDecimal.valueOf(feeSat).divide(currency.getDecimal());
        WithdrawRecord output = WithdrawRecord.builder()
                .withdrawId("btc-collection-" + now.getTime())
                .txId("collection")
                .address(hotAddress.getAddress())
                .userId(hotUserId)
                .balance(outputAmount)
                .currency(currency.getIndex())
                .biz(hotBiz)
                .fee(feeAmount)
                .status((byte) Constants.SIGNING)
                .createDate(now)
                .updateDate(now)
                .build();

        JSONObject signature = new JSONObject();
        signature.put("type", "collection");
        signature.put("collectionId", output.getWithdrawId());
        signature.put("utxos", utxos);
        signature.put("addresses", inputAddresses);
        signature.put("withdraw", List.of(output));
        signature.put("feeRate", feeRate);
        signature.put("totalAmount", inputAmount.toPlainString());

        WithdrawTransaction transaction = WithdrawTransaction.builder()
                .balance(inputAmount)
                .currency(currency.getIndex())
                .status(Constants.SIGNING)
                .txId("signing")
                .signature(signature.toJSONString())
                .createDate(now)
                .updateDate(now)
                .build();
        transactionService.add(transaction, table);

        String transactionId = transaction.getId().toString();
        List<UtxoTransaction> spends = utxos.stream().map(utxo -> UtxoTransaction.builder()
                .id(utxo.getId())
                .spent((byte) 1)
                .spentTxId(transactionId)
                .status((byte) Constants.SIGNING)
                .updateDate(now)
                .build()).toList();
        utxoService.batchEdit(spends, table);

        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
        log.info("BTC归集交易已创建 id={} inputs={} inputSat={} output={} feeSat={} feeRate={}",
                transaction.getId(), utxos.size(), inputSat, hotAddress.getAddress(), feeSat, feeRate);
    }

    private List<UtxoTransaction> findCollectableUtxos(ShardTable table, CurrencyEnum currency) {
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria()
                .andStatusEqualTo((byte) Constants.WAITING)
                .andSpentEqualTo((byte) 0)
                .andSpentTxIdEqualTo(UNSPENT_TX_ID)
                .andConfirmNumGreaterThanOrEqualTo(currency.getDepositConfirmNum());
        PageInfo pageInfo = new PageInfo();
        pageInfo.setPageSize(PAGE_SIZE);
        pageInfo.setStartIndex(0);
        pageInfo.setSortItem("id");
        pageInfo.setSortType(PageInfo.SORT_TYPE_ASC);
        List<UtxoTransaction> candidates = utxoService.getByPage(pageInfo, example, table);
        if (CollectionUtils.isEmpty(candidates)) {
            return candidates;
        }
        return candidates.stream()
                .filter(utxo -> {
                    Address address = addressService.getAddress(utxo.getAddress(), table);
                    return address != null && address.getUserId() != null && address.getUserId() > 0;
                })
                .toList();
    }

    private Address getHotAddress(ShardTable table) {
        AddressExample example = new AddressExample();
        example.createCriteria()
                .andUserIdEqualTo(hotUserId)
                .andBizEqualTo(hotBiz)
                .andIndexEqualTo(hotAddressIndex);
        return addressService.getOneByExample(example, table).orElse(null);
    }

    private int getFeeRate(CurrencyEnum currency) {
        Integer redisFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        if (redisFeeRate == null || redisFeeRate <= 0) {
            return DEFAULT_FEE_RATE;
        }
        return redisFeeRate;
    }

    private boolean isEnabled() {
        for (String item : enabledCurrencies.split(",")) {
            String value = item.trim();
            if ("*".equals(value) || CurrencyEnum.BTC.getName().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

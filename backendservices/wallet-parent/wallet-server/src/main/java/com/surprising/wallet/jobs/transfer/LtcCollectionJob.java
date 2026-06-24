package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
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

@Slf4j
@Component
public class LtcCollectionJob {
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final ChainJdbcRepository chainJdbcRepository;

    @Value("${atomex.wallet.collection.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.hot.user-id:0}")
    private Long hotUserId;

    @Value("${atomex.wallet.hot.biz:0}")
    private Integer hotBiz;

    @Value("${atomex.wallet.hot.address-index:0}")
    private Integer hotAddressIndex;

    public LtcCollectionJob(AddressService addressService,
                            ChainJdbcRepository chainJdbcRepository) {
        this.addressService = addressService;
        this.chainJdbcRepository = chainJdbcRepository;
    }

    @Scheduled(cron = "20/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        CurrencyEnum currency = CurrencyEnum.LTC;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        Address hotAddress = getHotAddress(table);
        if (hotAddress == null) {
            log.warn("LTC collection skipped: hot address missing userId={} biz={} index={}", hotUserId, hotBiz, hotAddressIndex);
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
        long inputLitoshi = inputAmount.multiply(currency.getDecimal()).longValue();
        long feeLitoshi = P2wshFeeCalculator.calculateFeeSat(inputAddresses.size(), 1, feeRate);
        long outputLitoshi = inputLitoshi - feeLitoshi;
        if (outputLitoshi < LitecoinFeePolicy.DUST_THRESHOLD_LITOSHI) {
            log.warn("LTC collection skipped: amount={} fee={} output below dust", inputLitoshi, feeLitoshi);
            return;
        }

        Date now = Date.from(Instant.now());
        BigDecimal outputAmount = BigDecimal.valueOf(outputLitoshi).divide(currency.getDecimal());
        BigDecimal feeAmount = BigDecimal.valueOf(feeLitoshi).divide(currency.getDecimal());
        String collectionId = "ltc-collection-" + utxos.get(0).getTxId() + "-" + utxos.get(0).getSeq();
        WithdrawRecord output = WithdrawRecord.builder()
                .withdrawId(collectionId)
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
        signature.put("collectionId", collectionId);
        signature.put("utxos", utxos);
        signature.put("addresses", inputAddresses);
        signature.put("withdraw", List.of(output));
        signature.put("feeRate", feeRate);
        signature.put("totalAmount", inputAmount.toPlainString());
        String rawPayload = signature.toJSONString();

        String fromAddress = inputAddresses.get(0).getAddress();
        chainJdbcRepository.createCollectionRecord(
                collectionId,
                "LTC",
                "LTC",
                fromAddress,
                hotAddress.getAddress(),
                outputAmount,
                feeAmount,
                rawPayload);
        if (chainJdbcRepository.claimCollectionSigning("LTC", collectionId, rawPayload) != 1) {
            return;
        }

        WithdrawTransaction transaction = WithdrawTransaction.builder()
                .balance(inputAmount)
                .currency(currency.getIndex())
                .status(Constants.SIGNING)
                .txId("signing")
                .signature(rawPayload)
                .createDate(now)
                .updateDate(now)
                .build();
        transaction = chainJdbcRepository.createBitcoinLikeSigningTransaction(
                currency, "COLLECTION", collectionId, transaction);

        String transactionId = transaction.getId().toString();
        for (UtxoTransaction utxo : utxos) {
            int locked = chainJdbcRepository.lockUtxo(
                    "LTC", utxo.getTxId(), utxo.getSeq(), transactionId);
            if (locked != 1) {
                throw new IllegalStateException(
                        "failed to lock unified LTC collection UTXO " + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
        log.info("LTC collection transaction created id={} inputs={} inputLitoshi={} output={} fee={} feeRate={}",
                transaction.getId(), utxos.size(), inputLitoshi, hotAddress.getAddress(), feeLitoshi, feeRate);
    }

    private List<UtxoTransaction> findCollectableUtxos(ShardTable table, CurrencyEnum currency) {
        List<UtxoTransaction> candidates = chainJdbcRepository.listSpendableUtxos(
                "LTC", "LTC", currency.getDepositConfirmNum(), PAGE_SIZE, 0);
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
        return chainJdbcRepository.findChainAddress(
                        "LTC", "LTC", hotUserId, hotBiz, hotAddressIndex, "DEPOSIT")
                .map(record -> Address.builder()
                        .address(record.getAddress())
                        .userId(record.getUserId())
                        .biz(record.getBiz())
                        .index(Math.toIntExact(record.getAddressIndex()))
                        .currency(CurrencyEnum.LTC.getName())
                        .build())
                .orElse(null);
    }

    private int getFeeRate(CurrencyEnum currency) {
        Integer redisFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        if (redisFeeRate == null || redisFeeRate <= 0) {
            return (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
        }
        return (int) LitecoinFeePolicy.clampFeeRate(redisFeeRate);
    }

    private boolean isEnabled() {
        for (String item : enabledCurrencies.split(",")) {
            String value = item.trim();
            if ("*".equals(value) || CurrencyEnum.LTC.getName().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

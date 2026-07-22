package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
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
    private static final String CHAIN = "LTC";
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final ChainJdbcRepository chainJdbcRepository;
    private final BlockchainRuntimeService blockchainRuntimeService;
    private final WalletRuntimeConfigService runtimeConfigService;

    public LtcCollectionJob(AddressService addressService,
                            ChainJdbcRepository chainJdbcRepository,
                            BlockchainRuntimeService blockchainRuntimeService,
                            WalletRuntimeConfigService runtimeConfigService) {
        this.addressService = addressService;
        this.chainJdbcRepository = chainJdbcRepository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @Scheduled(cron = "20/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(CHAIN);
        Address hotAddress = getHotAddress(currency);
        if (hotAddress == null) {
            log.warn("LTC collection skipped: hot address missing userId={} biz={} index={}",
                    HotWalletRules.DEFAULT_HOT_USER_ID,
                    HotWalletRules.DEFAULT_HOT_BIZ,
                    HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX);
            return;
        }

        List<UtxoTransaction> utxos = findCollectableUtxos(currency);
        if (CollectionUtils.isEmpty(utxos)) {
            return;
        }

        List<Address> inputAddresses = new ArrayList<>();
        BigDecimal inputAmount = BigDecimal.ZERO;
        for (UtxoTransaction utxo : utxos) {
            Address address = addressService.getAddress(utxo.getAddress(), currency);
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
                .userId(HotWalletRules.DEFAULT_HOT_USER_ID)
                .balance(outputAmount)
                .currency(currency.getIndex())
                .biz(HotWalletRules.DEFAULT_HOT_BIZ)
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
                CHAIN,
                CHAIN,
                fromAddress,
                hotAddress.getAddress(),
                outputAmount,
                feeAmount,
                rawPayload);
        if (chainJdbcRepository.claimCollectionSigning(null, CHAIN, collectionId, rawPayload) != 1) {
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
        currency.applyTo(transaction);
        transaction = chainJdbcRepository.createBitcoinLikeSigningTransaction(
                currency, "COLLECTION", collectionId, transaction);

        String transactionId = transaction.getId().toString();
        for (UtxoTransaction utxo : utxos) {
            int locked = chainJdbcRepository.lockUtxo(
                    CHAIN, utxo.getTxId(), utxo.getSeq(), transactionId);
            if (locked != 1) {
                throw new IllegalStateException(
                        "failed to lock unified LTC collection UTXO " + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
        log.info("LTC collection transaction created id={} inputs={} inputLitoshi={} output={} fee={} feeRate={}",
                transaction.getId(), utxos.size(), inputLitoshi, hotAddress.getAddress(), feeLitoshi, feeRate);
    }

    private List<UtxoTransaction> findCollectableUtxos(AssetRuntimeMetadata currency) {
        List<UtxoTransaction> candidates = chainJdbcRepository.listSpendableUtxos(
                CHAIN, CHAIN, currency.getDepositConfirmNum(), PAGE_SIZE, 0);
        if (CollectionUtils.isEmpty(candidates)) {
            return candidates;
        }
        return candidates.stream()
                .filter(utxo -> {
                    Address address = addressService.getAddress(utxo.getAddress(), currency);
                    return address != null && address.getUserId() != null && address.getUserId() > 0;
                })
                .toList();
    }

    private Address getHotAddress(AssetRuntimeMetadata currency) {
        return chainJdbcRepository.findChainAddress(
                        CHAIN,
                        CHAIN,
                        HotWalletRules.DEFAULT_HOT_USER_ID,
                        HotWalletRules.DEFAULT_HOT_BIZ,
                        HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                        HotWalletRules.DEFAULT_HOT_WALLET_ROLE)
                .map(record -> Address.builder()
                        .address(record.getAddress())
                        .userId(record.getUserId())
                        .biz(record.getBiz())
                        .index(Math.toIntExact(record.getAddressIndex()))
                        .currency(currency.getName())
                        .build())
                .orElse(null);
    }

    private int getFeeRate(AssetRuntimeMetadata currency) {
        Integer redisFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        if (redisFeeRate == null || redisFeeRate <= 0) {
            return (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
        }
        return (int) LitecoinFeePolicy.clampFeeRate(redisFeeRate);
    }

    private boolean isEnabled() {
        return runtimeConfigService.isTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION);
    }
}

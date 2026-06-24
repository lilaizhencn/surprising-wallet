package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
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

/**
 * Dogecoin legacy P2SH UTXO collection.
 */
@Component
public class DogeCollectionJob {
    private static final String CHAIN = "DOGE";
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final ChainJdbcRepository chainRepository;
    private final AssetRoutingService assetRoutingService;

    @Value("${atomex.wallet.collection.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.hot.user-id:0}")
    private Long hotUserId;

    @Value("${atomex.wallet.hot.biz:0}")
    private Integer hotBiz;

    @Value("${atomex.wallet.hot.address-index:0}")
    private Integer hotAddressIndex;

    public DogeCollectionJob(AddressService addressService,
                             ChainJdbcRepository chainRepository,
                             AssetRoutingService assetRoutingService) {
        this.addressService = addressService;
        this.chainRepository = chainRepository;
        this.assetRoutingService = assetRoutingService;
    }

    @Scheduled(cron = "22/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        RuntimeAsset currency = assetRoutingService.runtimeAssetByChain(CHAIN);
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        Address hotAddress = getHotAddress(table, currency);
        if (hotAddress == null) {
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
        if (inputAddresses.isEmpty()) {
            return;
        }

        int feeRate = getFeeRate(currency);
        long inputKoinu = inputAmount.multiply(currency.getDecimal()).longValueExact();
        long bytes = P2shMultisigFeeCalculator.estimateBytes(inputAddresses.size(), 1, 2, 3);
        long feeKoinu = DogecoinFeePolicy.feeForBytes(bytes, feeRate);
        long outputKoinu = inputKoinu - feeKoinu;
        if (outputKoinu < DogecoinFeePolicy.RECOMMENDED_DUST_THRESHOLD_KOINU) {
            return;
        }

        Date now = Date.from(Instant.now());
        BigDecimal outputAmount = BigDecimal.valueOf(outputKoinu).divide(currency.getDecimal());
        BigDecimal feeAmount = BigDecimal.valueOf(feeKoinu).divide(currency.getDecimal());
        String collectionId = "doge-collection-" + utxos.get(0).getTxId() + "-" + utxos.get(0).getSeq();
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

        chainRepository.createCollectionRecord(
                collectionId, CHAIN, CHAIN,
                inputAddresses.get(0).getAddress(), hotAddress.getAddress(),
                outputAmount, feeAmount, rawPayload);
        if (chainRepository.claimCollectionSigning(CHAIN, collectionId, rawPayload) != 1) {
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
        transaction = chainRepository.createBitcoinLikeSigningTransaction(
                currency, "COLLECTION", collectionId, transaction);

        String transactionId = transaction.getId().toString();
        for (UtxoTransaction utxo : utxos) {
            if (chainRepository.lockUtxo(CHAIN, utxo.getTxId(), utxo.getSeq(), transactionId) != 1) {
                throw new IllegalStateException(
                        "failed to lock DOGE collection UTXO " + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
    }

    private List<UtxoTransaction> findCollectableUtxos(ShardTable table, RuntimeAsset currency) {
        List<UtxoTransaction> candidates = chainRepository.listSpendableUtxos(
                CHAIN, CHAIN, currency.getDepositConfirmNum(), PAGE_SIZE, 0);
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

    private Address getHotAddress(ShardTable table, RuntimeAsset currency) {
        return chainRepository.findChainAddress(
                        CHAIN, CHAIN, hotUserId, hotBiz, hotAddressIndex, "DEPOSIT")
                .map(record -> Address.builder()
                        .address(record.getAddress())
                        .userId(record.getUserId())
                        .biz(record.getBiz())
                        .index(Math.toIntExact(record.getAddressIndex()))
                        .currency(currency.getName())
                        .build())
                .orElse(null);
    }

    private int getFeeRate(RuntimeAsset currency) {
        Integer configured = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        long feeRate = configured == null || configured <= 0
                ? DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE : configured;
        return (int) DogecoinFeePolicy.clampFeeRate(feeRate);
    }

    private boolean isEnabled() {
        for (String item : enabledCurrencies.split(",")) {
            String value = item.trim();
            if ("*".equals(value) || CHAIN.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

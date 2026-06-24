package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Component
public class BchCollectionJob {
    private static final String CHAIN = "BCH";
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final ChainJdbcRepository repository;
    private final AssetRoutingService assetRoutingService;

    @Value("${atomex.wallet.collection.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.hot.user-id:0}")
    private Long hotUserId;

    @Value("${atomex.wallet.hot.biz:0}")
    private Integer hotBiz;

    @Value("${atomex.wallet.hot.address-index:0}")
    private Integer hotAddressIndex;

    @Value("${atomex.bch.network:testnet}")
    private String network;

    public BchCollectionJob(
            AddressService addressService,
            ChainJdbcRepository repository,
            AssetRoutingService assetRoutingService) {
        this.addressService = addressService;
        this.repository = repository;
        this.assetRoutingService = assetRoutingService;
    }

    @Scheduled(cron = "24/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        RuntimeAsset currency = assetRoutingService.runtimeAssetByChain(CHAIN);
        BitcoinLikeChainProfile profile = profile();
        Address hotAddress = getHotAddress(currency);
        if (hotAddress == null) {
            return;
        }
        List<UtxoTransaction> utxos =
                findCollectableUtxos(currency, profile.getDepositConfirmations());
        if (utxos.isEmpty()) {
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
        if (inputAddresses.isEmpty()) {
            return;
        }

        long feeRate = profile.getDefaultFeeRate() == null
                ? BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE
                : Math.max(1L, profile.getDefaultFeeRate());
        long atomicAmount = inputAmount.multiply(currency.getDecimal()).longValueExact();
        long estimatedBytes =
                P2shMultisigFeeCalculator.estimateBytes(utxos.size(), 1, 2, 3);
        long fee = estimatedBytes * feeRate;
        long outputValue = atomicAmount - fee;
        long dust = profile.getDustThreshold() == null
                ? BitcoinCashFeePolicy.DUST_THRESHOLD_SAT
                : profile.getDustThreshold();
        if (outputValue < dust) {
            return;
        }

        Date now = Date.from(Instant.now());
        String collectionId =
                "bch-collection-" + utxos.get(0).getTxId() + "-" + utxos.get(0).getSeq();
        BigDecimal outputAmount =
                BigDecimal.valueOf(outputValue).divide(currency.getDecimal());
        BigDecimal feeAmount = BigDecimal.valueOf(fee).divide(currency.getDecimal());
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
        signature.put("dustThreshold", dust);
        signature.put("totalAmount", inputAmount.toPlainString());
        String rawPayload = signature.toJSONString();

        repository.createCollectionRecord(
                collectionId,
                CHAIN,
                CHAIN,
                inputAddresses.get(0).getAddress(),
                hotAddress.getAddress(),
                outputAmount,
                feeAmount,
                rawPayload);
        if (repository.claimCollectionSigning(CHAIN, collectionId, rawPayload) != 1) {
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
        transaction = repository.createBitcoinLikeSigningTransaction(
                currency, "COLLECTION", collectionId, transaction);
        String transactionId = transaction.getId().toString();
        for (UtxoTransaction utxo : utxos) {
            if (repository.lockUtxo(
                    CHAIN, utxo.getTxId(), utxo.getSeq(), transactionId) != 1) {
                throw new IllegalStateException(
                        "BCH UTXO lock failed " + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }
        REDIS.lPush(
                Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,
                JSONObject.toJSONString(transaction));
    }

    private List<UtxoTransaction> findCollectableUtxos(
            RuntimeAsset currency, int requiredConfirmations) {
        return repository.listSpendableUtxos(CHAIN, CHAIN, requiredConfirmations, PAGE_SIZE, 0).stream()
                .filter(utxo -> {
                    Address address = addressService.getAddress(utxo.getAddress(), currency);
                    return address != null
                            && address.getUserId() != null
                            && address.getUserId() > 0;
                })
                .toList();
    }

    private Address getHotAddress(RuntimeAsset currency) {
        return repository.findChainAddress(
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

    private BitcoinLikeChainProfile profile() {
        String profileNetwork = isMainnet() ? "mainnet" : isRegtest() ? "regtest" : "testnet";
        return repository.findBitcoinLikeProfile("BCH", profileNetwork)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for BCH/" + profileNetwork));
    }

    private boolean isEnabled() {
        return Arrays.stream(enabledCurrencies.split(","))
                .map(String::trim)
                .anyMatch(value -> "*".equals(value) || "bch".equalsIgnoreCase(value));
    }

    private boolean isMainnet() {
        return "main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network);
    }

    private boolean isRegtest() {
        return "regtest".equalsIgnoreCase(network);
    }
}

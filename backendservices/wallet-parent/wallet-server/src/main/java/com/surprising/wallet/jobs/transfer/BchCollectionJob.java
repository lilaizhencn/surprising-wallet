package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
public class BchCollectionJob {
    private static final String CHAIN = "BCH";
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final ChainJdbcRepository repository;
    private final BlockchainRuntimeService blockchainRuntimeService;
    private final WalletRuntimeConfigService runtimeConfigService;

    public BchCollectionJob(
            AddressService addressService,
            ChainJdbcRepository repository,
            BlockchainRuntimeService blockchainRuntimeService,
            WalletRuntimeConfigService runtimeConfigService) {
        this.addressService = addressService;
        this.repository = repository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @Scheduled(cron = "24/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(CHAIN);
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
        if (repository.claimCollectionSigning(null, CHAIN, collectionId, rawPayload) != 1) {
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
            AssetRuntimeMetadata currency, int requiredConfirmations) {
        return repository.listSpendableUtxos(CHAIN, CHAIN, requiredConfirmations, PAGE_SIZE, 0).stream()
                .filter(utxo -> {
                    Address address = addressService.getAddress(utxo.getAddress(), currency);
                    return address != null
                            && address.getUserId() != null
                            && address.getUserId() > 0;
                })
                .toList();
    }

    private Address getHotAddress(AssetRuntimeMetadata currency) {
        return repository.findChainAddress(
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

    private BitcoinLikeChainProfile profile() {
        String profileNetwork = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for BCH"))
                .getNetwork();
        return repository.findBitcoinLikeProfile("BCH", profileNetwork)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for BCH/" + profileNetwork));
    }

    private boolean isEnabled() {
        return runtimeConfigService.isTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION);
    }

}

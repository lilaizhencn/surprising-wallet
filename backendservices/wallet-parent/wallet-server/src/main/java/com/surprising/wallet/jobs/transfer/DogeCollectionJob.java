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
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
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

/**
 * Dogecoin legacy P2SH UTXO collection.
 */
@Component
public class DogeCollectionJob {
    private static final int PAGE_SIZE = 10;

    private final AddressService addressService;
    private final UtxoTransactionService utxoService;
    private final WithdrawTransactionService transactionService;
    private final ChainJdbcRepository chainRepository;

    @Value("${atomex.wallet.collection.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.hot.user-id:0}")
    private Long hotUserId;

    @Value("${atomex.wallet.hot.biz:0}")
    private Integer hotBiz;

    @Value("${atomex.wallet.hot.address-index:0}")
    private Integer hotAddressIndex;

    public DogeCollectionJob(AddressService addressService,
                             UtxoTransactionService utxoService,
                             WithdrawTransactionService transactionService,
                             ChainJdbcRepository chainRepository) {
        this.addressService = addressService;
        this.utxoService = utxoService;
        this.transactionService = transactionService;
        this.chainRepository = chainRepository;
    }

    @Scheduled(cron = "22/30 * * * * ?")
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        CurrencyEnum currency = CurrencyEnum.DOGE;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        Address hotAddress = getHotAddress(table);
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
                collectionId, "DOGE", "DOGE",
                inputAddresses.get(0).getAddress(), hotAddress.getAddress(),
                outputAmount, feeAmount, rawPayload);
        if (chainRepository.claimCollectionSigning("DOGE", collectionId, rawPayload) != 1) {
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
        transactionService.add(transaction, table);

        String transactionId = transaction.getId().toString();
        for (UtxoTransaction utxo : utxos) {
            if (chainRepository.lockUtxo("DOGE", utxo.getTxId(), utxo.getSeq(), transactionId) != 1) {
                throw new IllegalStateException(
                        "failed to lock DOGE collection UTXO " + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }
        List<UtxoTransaction> spends = utxos.stream().map(utxo -> UtxoTransaction.builder()
                .id(utxo.getId())
                .spent((byte) 1)
                .spentTxId(transactionId)
                .status((byte) Constants.SIGNING)
                .updateDate(now)
                .build()).toList();
        utxoService.batchEdit(spends, table);
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
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
        Integer configured = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        long feeRate = configured == null || configured <= 0
                ? DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE : configured;
        return (int) DogecoinFeePolicy.clampFeeRate(feeRate);
    }

    private boolean isEnabled() {
        for (String item : enabledCurrencies.split(",")) {
            String value = item.trim();
            if ("*".equals(value) || CurrencyEnum.DOGE.getName().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

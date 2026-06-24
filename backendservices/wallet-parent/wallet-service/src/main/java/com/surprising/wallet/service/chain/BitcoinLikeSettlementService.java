package com.surprising.wallet.service.chain;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.UserAssetService;
import com.surprising.wallet.service.service.WithdrawRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Atomically settles confirmed database-driven Bitcoin-like withdrawals and
 * collections across the legacy tables and unified chain ledger.
 */
@Service
public class BitcoinLikeSettlementService {
    private final ChainJdbcRepository chainRepository;
    private final AssetRoutingService assetRoutingService;
    private final UserAssetService userAssetService;
    private final WithdrawRecordService recordService;

    public BitcoinLikeSettlementService(ChainJdbcRepository chainRepository,
                                        AssetRoutingService assetRoutingService,
                                        UserAssetService userAssetService,
                                        WithdrawRecordService recordService) {
        this.chainRepository = chainRepository;
        this.assetRoutingService = assetRoutingService;
        this.userAssetService = userAssetService;
        this.recordService = recordService;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void settleConfirmed(WithdrawTransaction transaction, String txId, CurrencyEnum currency) {
        if (!assetRoutingService.isBitcoinLikeRuntimeCurrency(currency)) {
            throw new IllegalArgumentException("unsupported unified UTXO currency " + currency);
        }
        String chain = assetRoutingService.requireChainForRuntimeCurrencyId(currency.getIndex());
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());

        transaction.setStatus(Constants.CONFIRM);
        transaction.setUpdateDate(Date.from(Instant.now()));
        chainRepository.updateBitcoinLikeSigningTransaction(currency, transaction);

        if ("collection".equals(signature.getString("type"))) {
            chainRepository.markCollectionConfirmed(
                    chain, signature.getString("collectionId"), txId);
            chainRepository.markUtxosSpent(chain, transaction.getId().toString(), txId);
            return;
        }

        WithdrawRecordExample recordExample = new WithdrawRecordExample();
        recordExample.createCriteria().andTxIdEqualTo(txId);
        List<WithdrawRecord> records = recordService.getByExample(recordExample, table);
        for (WithdrawRecord record : records) {
            int confirmed = chainRepository.markWithdrawalConfirmed(
                    chain, record.getWithdrawId(), txId);
            if (confirmed == 1) {
                BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
                BigDecimal settled = record.getBalance().add(fee);
                if (!userAssetService.deduct(record.getUserId(), currency.getIndex(), settled)) {
                    throw new IllegalStateException(
                            "failed to settle " + chain + " frozen balance for " + record.getWithdrawId());
                }
                if (!chainRepository.settleLockedDebit(
                        chain, chain, record.getUserId().toString(), settled)) {
                    throw new IllegalStateException(
                            "failed to settle " + chain + " ledger for " + record.getWithdrawId());
                }
            }
            record.setStatus((byte) Constants.CONFIRM);
            record.setUpdateDate(Date.from(Instant.now()));
        }
        if (!records.isEmpty()) {
            recordService.batchEdit(records, table);
        }
        chainRepository.markUtxosSpent(chain, transaction.getId().toString(), txId);
    }
}

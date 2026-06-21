package com.surprising.wallet.service.chain.ltc;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.UserAssetService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.service.WithdrawRecordService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Atomically settles confirmed Litecoin withdrawals and collections across the
 * legacy tables and the unified chain ledger.
 */
@Service
public class LitecoinSettlementService {
    private final ChainJdbcRepository chainRepository;
    private final UserAssetService userAssetService;
    private final UtxoTransactionService utxoService;
    private final WithdrawRecordService recordService;
    private final WithdrawTransactionService transactionService;

    public LitecoinSettlementService(ChainJdbcRepository chainRepository,
                                     UserAssetService userAssetService,
                                     UtxoTransactionService utxoService,
                                     WithdrawRecordService recordService,
                                     WithdrawTransactionService transactionService) {
        this.chainRepository = chainRepository;
        this.userAssetService = userAssetService;
        this.utxoService = utxoService;
        this.recordService = recordService;
        this.transactionService = transactionService;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void settleConfirmed(WithdrawTransaction transaction, String txId) {
        CurrencyEnum currency = CurrencyEnum.LTC;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());

        transaction.setStatus(Constants.CONFIRM);
        transaction.setUpdateDate(Date.from(Instant.now()));
        transactionService.editById(transaction, table);

        UtxoTransactionExample utxoExample = new UtxoTransactionExample();
        utxoExample.createCriteria().andSpentTxIdEqualTo(txId);
        List<UtxoTransaction> legacyUtxos = utxoService.getByExample(utxoExample, table);
        legacyUtxos.forEach(utxo -> {
            utxo.setStatus((byte) Constants.CONFIRM);
            utxo.setUpdateDate(Date.from(Instant.now()));
        });
        if (!legacyUtxos.isEmpty()) {
            utxoService.batchEdit(legacyUtxos, table);
        }

        if ("collection".equals(signature.getString("type"))) {
            chainRepository.markCollectionConfirmed(
                    "LTC", signature.getString("collectionId"), txId);
            chainRepository.markUtxosSpent("LTC", transaction.getId().toString(), txId);
            return;
        }

        WithdrawRecordExample recordExample = new WithdrawRecordExample();
        recordExample.createCriteria().andTxIdEqualTo(txId);
        List<WithdrawRecord> records = recordService.getByExample(recordExample, table);
        for (WithdrawRecord record : records) {
            int confirmed = chainRepository.markWithdrawalConfirmed(
                    "LTC", record.getWithdrawId(), txId);
            if (confirmed == 1) {
                BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
                BigDecimal settled = record.getBalance().add(fee);
                if (!userAssetService.deduct(record.getUserId(), currency.getIndex(), settled)) {
                    throw new IllegalStateException(
                            "failed to settle LTC frozen balance for " + record.getWithdrawId());
                }
                if (!chainRepository.settleLockedDebit(
                        "LTC", "LTC", record.getUserId().toString(), settled)) {
                    throw new IllegalStateException(
                            "failed to settle LTC ledger for " + record.getWithdrawId());
                }
            }
            record.setStatus((byte) Constants.CONFIRM);
            record.setUpdateDate(Date.from(Instant.now()));
        }
        if (!records.isEmpty()) {
            recordService.batchEdit(records, table);
        }
        chainRepository.markUtxosSpent("LTC", transaction.getId().toString(), txId);
    }
}

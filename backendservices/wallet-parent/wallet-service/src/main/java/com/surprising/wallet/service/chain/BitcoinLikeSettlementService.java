package com.surprising.wallet.service.chain;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Atomically settles confirmed database-driven Bitcoin-like withdrawals and
 * collections across withdrawal_order, collection_record, utxo_record and
 * ledger_balance.
 */
@Service
public class BitcoinLikeSettlementService {
    private final ChainJdbcRepository chainRepository;

    public BitcoinLikeSettlementService(ChainJdbcRepository chainRepository) {
        this.chainRepository = chainRepository;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void settleConfirmed(WithdrawTransaction transaction, String txId, AssetRuntimeMetadata currency) {
        ChainType chainType = ChainType.valueOf(currency.chain());
        if (!chainType.isUtxo()) {
            throw new IllegalArgumentException("unsupported unified UTXO currency " + currency);
        }
        String chain = currency.chain();
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

        List<WithdrawRecord> records = signature.getJSONArray("withdraw") == null
                ? List.of()
                : signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        for (WithdrawRecord record : records) {
            int confirmed = chainRepository.markWithdrawalConfirmed(
                    chain, record.getWithdrawId(), txId);
            if (confirmed == 1) {
                BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
                BigDecimal settled = record.getBalance().add(fee);
                String debitAccountId = chainRepository.findWithdrawalOrder(chain, record.getWithdrawId())
                        .map(WithdrawalOrderRecord::getDebitAccountId)
                        .filter(value -> value != null && !value.isBlank())
                        .orElse(record.getUserId().toString());
                if (!chainRepository.settleLockedDebit(
                        chain, chain, debitAccountId, settled)) {
                    throw new IllegalStateException(
                            "failed to settle " + chain + " ledger for " + record.getWithdrawId());
                }
            }
            record.setStatus((byte) Constants.CONFIRM);
            record.setUpdateDate(Date.from(Instant.now()));
        }
        chainRepository.markUtxosSpent(chain, transaction.getId().toString(), txId);
    }
}

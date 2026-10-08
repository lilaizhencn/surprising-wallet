package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
public class BitcoinLikeSettlementService {
    /**
     * 保存 {@code chainRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository chainRepository;
    /** Jackson 3 对象映射器，用于解析提现签名元数据。 */
    private final ObjectMapper objectMapper;
    /**
     * 构造 {@code BitcoinLikeSettlementService}，初始化该组件运行所需的状态和依赖。
     */
    public BitcoinLikeSettlementService(ChainJdbcRepository chainRepository, ObjectMapper objectMapper) {
        this.chainRepository = chainRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 设置或更新 {@code settleConfirmed} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void settleConfirmed(WithdrawTransaction transaction, String txId, AssetRuntimeMetadata currency) {
        ChainType chainType = ChainType.valueOf(currency.chain());
        if (!chainType.isUtxo()) {
            throw new IllegalArgumentException("unsupported unified UTXO currency " + currency);
        }
        String chain = currency.chain();
        transaction = chainRepository.lockBitcoinLikeSigningTransaction(currency, transaction.getId())
                .orElseThrow(() -> new IllegalStateException("settlement transaction missing"));
        if (transaction.getStatus() == Constants.CONFIRM || transaction.getStatus() == Constants.DELETE) return;
        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
        if (!txId.equals(transaction.getTxId()) && !BitcoinLikeRbfHistory.hasHistory(signature))
            throw new IllegalArgumentException("settlement transaction hash mismatch");
        if (!txId.equals(transaction.getTxId()) && transaction.getTxId() != null
                && transaction.getTxId().matches("[0-9a-fA-F]{64}"))
            BitcoinLikeRbfHistory.archive(signature, transaction.getTxId());
        signature = BitcoinLikeRbfHistory.confirmedSignature(signature, transaction.getTxId(), txId);
        transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
        transaction.setTxId(txId);
        transaction.setStatus(Constants.CONFIRM);
        transaction.setUpdateDate(Date.from(Instant.now()));
        chainRepository.updateBitcoinLikeSigningTransaction(currency, transaction);

        if ("COLLECTION".equals(signature.path("operationType").asText())) {
            java.util.UUID tenantId = java.util.UUID.fromString(signature.path("tenantId").asText());
            String collectionNo = signature.path("collectionNo").asText();
            List<WithdrawRecord> outputs = JacksonJson.toList(objectMapper, signature.get("withdraw"), WithdrawRecord.class);
            if (outputs.size() != 1) throw new IllegalStateException("collection settlement output missing");
            if (chainRepository.updateCollectionAmounts(tenantId, chain, collectionNo, outputs.getFirst().getBalance(),
                    BigDecimal.valueOf(signature.path("fee").asLong()).divide(currency.getDecimal())) != 1)
                throw new IllegalStateException("collection settlement amounts missing");
            if (chainRepository.markCollectionConfirmed(tenantId, chain, collectionNo, txId) != 1) {
                throw new IllegalStateException("BTC collection record missing during settlement");
            }
            markInputsSpent(transaction, txId, currency, signature);
            return;
        }

        List<WithdrawRecord> records = signature.get("withdraw") == null
                ? List.of()
                : JacksonJson.toList(objectMapper, signature.get("withdraw"), WithdrawRecord.class);
        for (WithdrawRecord record : records) {
            BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
            BigDecimal settled = record.getBalance().add(fee);
            WithdrawalOrderRecord order = chainRepository.findWithdrawalOrder(chain, record.getWithdrawId())
                    .orElseThrow(() -> new IllegalStateException(
                            "missing withdrawal order " + chain + ":" + record.getWithdrawId()));
            java.util.UUID tenantId = java.util.Objects.requireNonNull(
                    order.getTenantId(), "withdrawal tenantId is required");
            String debitAccountId = java.util.Optional.ofNullable(order.getDebitAccountId())
                    .filter(value -> value != null && !value.isBlank())
                    .orElse(record.getUserId().toString());
            chainRepository.confirmWithdrawalAndSettle(
                    tenantId, chain, record.getWithdrawId(), txId, chain, debitAccountId, settled);
            record.setStatus((byte) Constants.CONFIRM);
            record.setUpdateDate(Date.from(Instant.now()));
        }
        markInputsSpent(transaction, txId, currency, signature);
    }
    private void markInputsSpent(WithdrawTransaction transaction, String txId,
                                 AssetRuntimeMetadata currency, ObjectNode signature) {
        int inputs = signature.path("utxos").size();
        if (inputs == 0 || chainRepository.markUtxosSpent(currency.chain(), transaction.getId().toString(), txId) != inputs)
            throw new IllegalStateException("confirmed transaction input lock mismatch");
    }

    /** Partial confirmations use the same lock and cannot reopen a confirmed operation. */
    @Transactional(rollbackFor = Throwable.class)
    public void markConfirming(int transactionId, String txId, AssetRuntimeMetadata currency) {
        var transaction = chainRepository.lockBitcoinLikeSigningTransaction(currency, transactionId).orElseThrow();
        if (transaction.getStatus() == Constants.CONFIRM || transaction.getStatus() == Constants.DELETE) return;
        ObjectNode signature = BitcoinLikeRbfHistory.confirmedSignature(
                JacksonJson.readObject(objectMapper, transaction.getSignature()), transaction.getTxId(), txId);
        java.util.UUID tenant = java.util.UUID.fromString(signature.path("tenantId").asText());
        if ("COLLECTION".equals(signature.path("operationType").asText())) {
            chainRepository.updateCollectionStatus(tenant, currency.chain(), signature.path("collectionNo").asText(),
                    "CONFIRMING", txId, null, null);
        } else {
            for (WithdrawRecord record : JacksonJson.toList(objectMapper, signature.get("withdraw"), WithdrawRecord.class))
                chainRepository.updateWithdrawalStatus(tenant, currency.chain(), record.getWithdrawId(),
                        "CONFIRMING", null, txId, null);
        }
    }

}

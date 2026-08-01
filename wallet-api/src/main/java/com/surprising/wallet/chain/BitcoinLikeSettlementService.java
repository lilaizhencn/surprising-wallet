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
        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());

        transaction.setStatus(Constants.CONFIRM);
        transaction.setUpdateDate(Date.from(Instant.now()));
        chainRepository.updateBitcoinLikeSigningTransaction(currency, transaction);

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
        chainRepository.markUtxosSpent(chain, transaction.getId().toString(), txId);
    }
}

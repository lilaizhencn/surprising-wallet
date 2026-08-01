package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 托管提现状态对账服务，负责把提现状态变化投影为 Webhook 事件。
 */
@Service
public class CustodyWithdrawalReconciliationService {
    /** 失败状态集合。 */
    private static final Set<String> FAILURE_STATES = Set.of("FAILED", "REJECTED", "CANCELLED");

    /** 托管数据仓储。 */
    private final CustodyRepository repository;
    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 构造提现状态对账服务。 */
    public CustodyWithdrawalReconciliationService(
            CustodyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 批量处理尚未投影为事件的提现状态变化。 */
    public void reconcile() {
        for (CustodyRepository.WithdrawalStatusChange change
                : repository.findWithdrawalStatusChanges(100)) {
            String eventType = eventType(change.nextStatus());
            UUID eventId = eventType == null ? null : UUID.randomUUID();
            repository.applyWithdrawalStatusChange(
                    change, eventId, eventType,
                    eventType == null ? null : payload(eventId, eventType, change));
        }
    }

    /** 将提现状态映射为事件类型。 */
    private String eventType(String status) {
        return switch (status) {
            case "SENT" -> "WITHDRAWAL.BROADCAST";
            case "BROADCAST_UNKNOWN" -> "WITHDRAWAL.BROADCAST_UNKNOWN";
            case "CONFIRMED" -> "WITHDRAWAL.CONFIRMED";
            default -> FAILURE_STATES.contains(status) ? "WITHDRAWAL.FAILED" : null;
        };
    }

    /** 生成提现事件 JSON。 */
    private String payload(UUID eventId, String eventType,
                           CustodyRepository.WithdrawalStatusChange change) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("withdrawalId", change.id());
        data.put("custodyAddressId", change.custodyAddressId());
        data.put("externalReference", change.externalReference());
        data.put("orderNo", change.orderNo());
        data.put("chain", change.chain());
        data.put("asset", change.assetSymbol());
        data.put("toAddress", change.toAddress());
        data.put("amount", change.amount());
        data.put("fee", change.fee());
        data.put("status", change.nextStatus());
        data.put("txHash", change.txHash());
        data.put("errorMessage", change.errorMessage());
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "id", eventId,
                    "type", eventType,
                    "createdAt", Instant.now(),
                    "data", data));
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize withdrawal event", e);
        }
    }
}

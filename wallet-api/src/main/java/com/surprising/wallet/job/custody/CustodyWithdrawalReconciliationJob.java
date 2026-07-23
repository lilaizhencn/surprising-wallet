package com.surprising.wallet.job.custody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository.WithdrawalStatusChange;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.custody.repository.CustodyRepository;

/**
 * 托管提现状态对账任务。
 * <p>
 * 每 500ms 执行一次：扫描 DB 中状态已变更但尚未生成事件的提现记录，
 * 写入对应的 custody_event（BROADCAST / CONFIRMED / FAILED），
 * 确保事件日志与提现实时状态一致，供 Webhook 和审计使用。
 *
 * @author atomex
 */
@Component
public class CustodyWithdrawalReconciliationJob {
    private static final Set<String> FAILURE_STATES = Set.of("FAILED", "REJECTED", "CANCELLED");

    private final CustodyRepository repository;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public CustodyWithdrawalReconciliationJob(CustodyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${sw.wallet.custody.withdrawal-reconcile-delay:500}")
    public void reconcile() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (WithdrawalStatusChange change : repository.findWithdrawalStatusChanges(100)) {
                String eventType = eventType(change.nextStatus());
                UUID eventId = eventType == null ? null : UUID.randomUUID();
                repository.applyWithdrawalStatusChange(
                        change, eventId, eventType,
                        eventType == null ? null : payload(eventId, eventType, change));
            }
        } finally {
            running.set(false);
        }
    }

    private String eventType(String status) {
        return switch (status) {
            case "SENT" -> "WITHDRAWAL.BROADCAST";
            case "BROADCAST_UNKNOWN" -> "WITHDRAWAL.BROADCAST_UNKNOWN";
            case "CONFIRMED" -> "WITHDRAWAL.CONFIRMED";
            default -> FAILURE_STATES.contains(status) ? "WITHDRAWAL.FAILED" : null;
        };
    }

    private String payload(UUID eventId, String eventType, WithdrawalStatusChange change) {
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize withdrawal event", e);
        }
    }
}

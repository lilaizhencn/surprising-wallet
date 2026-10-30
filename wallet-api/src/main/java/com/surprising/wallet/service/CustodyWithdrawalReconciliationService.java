package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyLedgerEntryRepository;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Project the current owned order, debit receipt, Gas and event in one short transaction. */
@Slf4j
@Service
public class CustodyWithdrawalReconciliationService {
    private final CustodyWithdrawalRepository withdrawals;
    private final WithdrawalOrderRepository orders;
    private final CustodyAddressRepository addresses;
    private final CustodyLedgerEntryRepository entries;
    private final CustodyGasReconciliationService gas;
    private final CustodyEventService events;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public CustodyWithdrawalReconciliationService(CustodyWithdrawalRepository withdrawals,
            WithdrawalOrderRepository orders, CustodyAddressRepository addresses,
            CustodyLedgerEntryRepository entries, CustodyGasReconciliationService gas,
            CustodyEventService events, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.withdrawals = withdrawals;
        this.orders = orders;
        this.addresses = addresses;
        this.entries = entries;
        this.gas = gas;
        this.events = events;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Scan fixed-size pages; old unchanged rows cannot hide later status changes. */
    public void reconcile() {
        UUID cursor = null;
        while (true) {
            var page = withdrawals.listReconciliationCandidates(cursor, 100);
            if (page.isEmpty()) return;
            cursor = page.getLast().id();
            var tenants = page.stream().collect(Collectors.groupingBy(CustodyWithdrawalRepository.StatusRow::tenantId));
            for (var tenant : tenants.entrySet()) {
                Map<Long, WithdrawalOrderRepository.HistoryOrder> current = orders.findHistoryOrders(tenant.getKey(),
                        tenant.getValue().stream().map(CustodyWithdrawalRepository.StatusRow::withdrawalOrderId).toList())
                        .stream().collect(Collectors.toMap(WithdrawalOrderRepository.HistoryOrder::id, row -> row));
                for (var row : tenant.getValue()) {
                    var order = current.get(row.withdrawalOrderId());
                    if (order != null && row.status().equals(order.status())) continue;
                    try { project(row.tenantId(), row.id()); }
                    catch (RuntimeException error) {
                        log.warn("custody withdrawal projection failed: withdrawalId={} error={}", row.id(), error.getMessage());
                    }
                }
            }
        }
    }

    public boolean project(UUID tenantId, UUID withdrawalId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId is required");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var row = withdrawals.lockProjection(tenantId, withdrawalId).orElse(null);
            if (row == null) return false;
            var order = orders.find(row.chain(), row.orderNo(), tenantId)
                    .orElseThrow(() -> new IllegalStateException("custody withdrawal order is missing"));
            if (row.withdrawalOrderId() != order.getId()) throw new IllegalStateException("custody withdrawal order identity mismatch");
            if (row.status().equals(order.getStatus())) return false;
            if (withdrawals.transitionStatus(tenantId, withdrawalId, row.status(), order.getStatus()) != 1) return false;
            if ("CONFIRMED".equals(order.getStatus())) {
                entries.insertIfAbsent(UUID.randomUUID(), tenantId, row.custodyAddressId(), row.chain(),
                        order.getAssetSymbol(), order.getDebitAccountId(), "WITHDRAWAL", "DEBIT",
                        order.getAmount().add(order.getFee()), "WITHDRAWAL", row.id().toString());
            }
            gas.reconcileWithdrawal(tenantId, withdrawalId);
            String eventType = eventType(order.getStatus());
            if (eventType != null) {
                UUID eventId = UUID.randomUUID();
                events.publish(eventId, tenantId, eventType, "WITHDRAWAL", row.orderNo(),
                        payload(eventId, eventType, row, order), "API_KEY".equals(row.createdByType()));
            }
            return true;
        }));
    }

    private static String eventType(String status) {
        return switch (status) {
            case "SENT" -> "WITHDRAWAL.BROADCAST";
            case "BROADCAST_UNKNOWN" -> "WITHDRAWAL.BROADCAST_UNKNOWN";
            case "CONFIRMED" -> "WITHDRAWAL.CONFIRMED";
            default -> Set.of("FAILED", "REJECTED", "CANCELLED").contains(status) ? "WITHDRAWAL.FAILED" : null;
        };
    }

    private String payload(UUID eventId, String eventType, CustodyWithdrawalRepository.StatusRow row,
                           WithdrawalOrderRecord order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("withdrawalId", row.id());
        data.put("custodyAddressId", row.custodyAddressId());
        data.put("externalReference", row.externalReference());
        data.put("orderNo", row.orderNo());
        data.put("chain", row.chain());
        data.put("subject",row.subject());
        data.put("fundingSource",row.fundingSource());
        data.put("asset", order.getAssetSymbol());
        data.put("toAddress", order.getToAddress());
        data.put("amount", order.getAmount());
        data.put("fee", order.getFee());
        data.put("status", order.getStatus());
        data.put("txHash", order.getTxHash());
        data.put("errorMessage", order.getErrorMessage());
        try {
            return mapper.writeValueAsString(Map.of("id", eventId, "type", eventType,
                    "createdAt", Instant.now(), "data", data));
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize withdrawal event", error);
        }
    }
}

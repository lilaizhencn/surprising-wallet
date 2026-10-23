package com.surprising.wallet.service;

import com.surprising.wallet.repository.*;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 已由 EVM 批次接管的订单不再由单笔确认流程重复处理。 */
@Service
@RequiredArgsConstructor
public class EvmBatchOwnershipService {
    private static final Set<String> PENDING = Set.of("SUBMITTED", "CONFIRMING", "BROADCAST_UNKNOWN");
    private final EvmWithdrawalBatchItemRepository withdrawalItems;
    private final EvmWithdrawalBatchRepository withdrawals;
    private final EvmCollectionBatchItemRepository collectionItems;
    private final EvmCollectionBatchRepository collections;

    public boolean withdrawalPending(UUID tenant, long orderId) {
        return withdrawalItems.listSubmittedByWithdrawal(tenant, orderId).stream()
                .map(row -> (UUID) row.get("batch_id"))
                .anyMatch(id -> withdrawals.findStatuses(tenant, id).stream().anyMatch(PENDING::contains));
    }

    public boolean collectionPending(UUID tenant, long collectionId) {
        return collectionItems.listSubmittedByCollection(tenant, collectionId).stream()
                .map(row -> (UUID) row.get("batch_id"))
                .anyMatch(id -> collections.findStatuses(tenant, id).stream().anyMatch(PENDING::contains));
    }
}

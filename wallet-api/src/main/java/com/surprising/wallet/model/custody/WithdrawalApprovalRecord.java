package com.surprising.wallet.model.custody;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import java.util.UUID;

/** Permanent local receipt links the original freeze to independent admission; it never grants signing by itself. */
public record WithdrawalApprovalRecord(UUID tenantId,UUID withdrawalId,long orderId,WithdrawalIntent intent,
        String state,ApprovalRequest receipt) { }

package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyReorgDeficitRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 领取租户提现进入签名，已有重组赤字时保留待处理状态。 */
@Service
@RequiredArgsConstructor
public class WithdrawalClaimService {
    private final WithdrawalOrderRepository orders;
    private final CustodyReorgDeficitRepository deficits;

    @Transactional(rollbackFor = Throwable.class)
    public int claim(UUID tenantId, String chain, String orderNo, String fromAddress) {
        Objects.requireNonNull(tenantId, "withdrawal tenantId is required");
        var order = orders.find(chain, orderNo, tenantId).orElse(null);
        if (order == null || (order.getDebitAccountId() != null && deficits.existsOpen(
                tenantId, chain, order.getAssetSymbol(), order.getDebitAccountId()))) return 0;
        return orders.claimSigning(tenantId, chain, orderNo, fromAddress);
    }
}

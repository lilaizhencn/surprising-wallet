package com.surprising.wallet.service;

import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Settle a confirmed tenant withdrawal and its frozen customer balance in one transaction. */
@Service
@RequiredArgsConstructor
public class WithdrawalSettlementService {
    private final WithdrawalOrderRepository orders;
    private final LedgerBalanceRepository balances;

    @Transactional(rollbackFor = Throwable.class)
    public boolean confirm(UUID tenantId, String chain, String orderNo, String txHash) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        var order = orders.lockForSettlement(tenantId, chain, orderNo)
                .orElseThrow(() -> new IllegalStateException("missing tenant withdrawal order " + chain + ":" + orderNo));
        if ("CONFIRMED".equals(order.getStatus())) {
            return false;
        }
        if (!Set.of("SENT", "CONFIRMING").contains(order.getStatus())) {
            throw new IllegalStateException("withdrawal " + orderNo + " is not confirmable from status " + order.getStatus());
        }
        if (order.getTxHash() == null || !order.getTxHash().equals(txHash)) {
            throw new IllegalStateException("withdrawal " + orderNo + " tx hash mismatch");
        }
        String assetSymbol = order.getAssetSymbol();
        String accountId = order.getDebitAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException("withdrawal " + orderNo + " debit account is missing");
        }
        BigDecimal amount = order.getAmount().add(order.getFee());
        if (!balances.settle(chain, assetSymbol, accountId, amount, tenantId)) {
            throw new IllegalStateException("unable to settle locked " + assetSymbol + " balance");
        }
        if (orders.markConfirmed(tenantId, chain, orderNo, txHash) != 1) {
            throw new IllegalStateException("unable to mark withdrawal " + orderNo + " confirmed");
        }
        return true;
    }
    /** Only a confirmed failure receipt permits releasing the frozen principal. */
    @Transactional(rollbackFor = Throwable.class)
    public boolean fail(UUID tenantId, String chain, String orderNo, String txHash, String reason) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (txHash == null || txHash.isBlank()) throw new IllegalArgumentException("failure receipt hash is required");
        var order = orders.lockForSettlement(tenantId,chain,orderNo).orElseThrow();
        if ("FAILED".equals(order.getStatus())) return false;
        if (!Set.of("SENT","CONFIRMING").contains(order.getStatus()) || !txHash.equals(order.getTxHash())) {
            throw new IllegalStateException("failure receipt differs from owned sent withdrawal");
        }
        if (!balances.release(chain,order.getAssetSymbol(),order.getDebitAccountId(),order.getAmount().add(order.getFee()),tenantId)) {
            throw new IllegalStateException("failed withdrawal locked principal is inconsistent");
        }
        if (orders.failSettled(tenantId,chain,orderNo,txHash,reason) != 1) throw new IllegalStateException("failed withdrawal transition lost");
        return true;
    }

}

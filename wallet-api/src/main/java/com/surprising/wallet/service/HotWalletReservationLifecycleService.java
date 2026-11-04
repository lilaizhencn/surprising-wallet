package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Closes an operating inventory hold only with its immutable order's verified terminal outcome. */
@Service
public class HotWalletReservationLifecycleService {
    private final CustodyHotWalletWithdrawalReservationRepository reservations;
    private final WithdrawalOrderRepository orders;

    public HotWalletReservationLifecycleService(CustodyHotWalletWithdrawalReservationRepository reservations,
                                                WithdrawalOrderRepository orders) {
        this.reservations = reservations;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Throwable.class)
    public boolean close(UUID tenantId, String chain, String orderNo, String outcome) {
        if (!java.util.Set.of("CONFIRMED", "FAILED", "CANCELLED").contains(outcome)) {
            throw new IllegalArgumentException("invalid hot wallet reservation outcome");
        }
        var order = orders.find(chain, orderNo, tenantId).orElseThrow();
        String expected = "CONFIRMED".equals(outcome) ? "CONFIRMED" : "FAILED";
        if (!expected.equals(order.getStatus())) {
            throw new IllegalStateException("hot wallet reservation cannot close before its order is terminal");
        }
        var reservation = reservations.lockByOrder(tenantId, chain, orderNo).orElse(null);
        if (reservation == null) return false;
        boolean sameSource = order.getFromAddress() != null && (ChainType.valueOf(chain).isEvm()
                ? order.getFromAddress().equalsIgnoreCase(reservation.sourceAddress())
                : order.getFromAddress().equals(reservation.sourceAddress()));
        if (!order.getAssetSymbol().equals(reservation.assetSymbol()) || !sameSource
                || order.getAmount().compareTo(reservation.amount()) != 0) {
            throw new IllegalStateException("hot wallet reservation differs from its original withdrawal");
        }
        if (outcome.equals(reservation.status())) return false;
        if (!"OPEN".equals(reservation.status())
                || reservations.markTerminal(tenantId, chain, orderNo, outcome) != 1) {
            throw new IllegalStateException("hot wallet reservation terminal outcome cannot change");
        }
        return true;
    }
}

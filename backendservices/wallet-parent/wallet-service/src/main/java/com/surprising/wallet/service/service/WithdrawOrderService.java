package com.surprising.wallet.service.service;

import com.surprising.wallet.common.pojo.WithdrawOrder;

import java.util.List;

public interface WithdrawOrderService {

    WithdrawOrder create(WithdrawOrder order);

    List<WithdrawOrder> getPendingOrders();

    void markSigned(Long id, String txId, String signatureData);

    void markFailed(Long id);
}

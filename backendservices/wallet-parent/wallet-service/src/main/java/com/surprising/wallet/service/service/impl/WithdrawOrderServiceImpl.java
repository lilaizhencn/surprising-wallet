package com.surprising.wallet.service.service.impl;

import com.surprising.wallet.common.pojo.WithdrawOrder;
import com.surprising.wallet.service.dao.WithdrawOrderRepository;
import com.surprising.wallet.service.service.WithdrawOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class WithdrawOrderServiceImpl implements WithdrawOrderService {

    @Autowired
    private WithdrawOrderRepository withdrawOrderRepository;

    @Override
    @Transactional
    public WithdrawOrder create(WithdrawOrder order) {
        withdrawOrderRepository.insert(order);
        return order;
    }

    @Override
    public List<WithdrawOrder> getPendingOrders() {
        return withdrawOrderRepository.selectByStatus(0, 100);
    }

    @Override
    @Transactional
    public void markSigned(Long id, String txId, String signatureData) {
        withdrawOrderRepository.updateSigned(id, 1, txId, signatureData);
    }

    @Override
    @Transactional
    public void markFailed(Long id) {
        withdrawOrderRepository.updateSigned(id, 3, "", null);
    }
}

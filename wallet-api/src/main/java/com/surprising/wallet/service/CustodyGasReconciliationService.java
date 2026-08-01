package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 托管 Gas 对账服务，负责结算数据库中已逾期的 Gas 预留记录。
 */
@Slf4j
@Service
public class CustodyGasReconciliationService {
    /** 托管数据仓储。 */
    private final CustodyRepository repository;

    /** 构造 Gas 对账服务。 */
    public CustodyGasReconciliationService(CustodyRepository repository) {
        this.repository = repository;
    }

    /** 批量结算逾期 Gas 使用记录。 */
    public void reconcile() {
        for (CustodyRepository.GasUsageRecord usage : repository.listOverdueGasUsage(100)) {
            try {
                repository.settleGasUsage(
                        usage.tenantId(), usage.operationType(), usage.operationId(),
                        usage.actualAmount(), usage.pricingSource(), usage.txHash());
            } catch (RuntimeException error) {
                log.warn("custody gas reconciliation failed: usageId={} error={}",
                        usage.id(), error.getMessage());
            }
        }
    }
}

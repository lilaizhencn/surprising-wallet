package com.surprising.wallet.job.custody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.custody.repository.CustodyRepository;

/**
 * 托管 Gas 费用对账任务。
 * <p>
 * 每 2 秒执行一次：扫描 DB 中状态为 OVERDUE 的 Gas 预留记录，
 * 逐笔调用 settleGasUsage 结算实际 Gas 消耗，确保 Gas 账户余额与链上一致。
 *
 * @author atomex
 */
@Component
public class CustodyGasReconciliationJob {
    private static final Logger log =
            LoggerFactory.getLogger(CustodyGasReconciliationJob.class);

    private final CustodyRepository repository;
    private final AtomicBoolean running = new AtomicBoolean();

    public CustodyGasReconciliationJob(CustodyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${sw.wallet.custody.gas-reconcile-delay:2000}")
    public void reconcile() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (CustodyRepository.GasUsageRecord usage
                    : repository.listOverdueGasUsage(100)) {
                try {
                    repository.settleGasUsage(
                            usage.tenantId(),
                            usage.operationType(),
                            usage.operationId(),
                            usage.actualAmount(),
                            usage.pricingSource(),
                            usage.txHash());
                } catch (RuntimeException error) {
                    log.warn(
                            "custody gas reconciliation failed: usageId={} error={}",
                            usage.id(), error.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}

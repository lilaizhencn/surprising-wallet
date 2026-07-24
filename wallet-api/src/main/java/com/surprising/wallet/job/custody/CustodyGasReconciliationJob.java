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
    /** 结构化日志实例。 */
    private static final Logger log =
            LoggerFactory.getLogger(CustodyGasReconciliationJob.class);

    /** 账务仓储。 */
    private final CustodyRepository repository;
    /** 防并发开关，避免重复 reconcile。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 每次调度执行 gas 用量结算。
     */
    public CustodyGasReconciliationJob(CustodyRepository repository) {
        this.repository = repository;
    }

    /**
     * 每 2 秒扫描 overdue gas usage 并尝试 settle。
     */
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

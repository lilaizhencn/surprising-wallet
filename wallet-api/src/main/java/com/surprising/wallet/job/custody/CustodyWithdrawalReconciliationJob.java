package com.surprising.wallet.job.custody;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.service.CustodyWithdrawalReconciliationService;

/**
 * 托管提现状态对账任务。
 * <p>
 * 每 500ms 执行一次：扫描 DB 中状态已变更但尚未生成事件的提现记录，
 * 写入对应的 custody_event（BROADCAST / CONFIRMED / FAILED），
 * 确保事件日志与提现实时状态一致，供 Webhook 和审计使用。
 *
 * @author atomex
 */
@Component
public class CustodyWithdrawalReconciliationJob {
    /** 提现状态对账服务。 */
    private final CustodyWithdrawalReconciliationService reconciliation;
    /** 防并发开关。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 构造注入仓储与序列化组件。
     */
    public CustodyWithdrawalReconciliationJob(
            CustodyWithdrawalReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * 500ms 拉取状态变更并落 custody_event，保持事件一致性。
     */
    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${sw.wallet.custody.withdrawal-reconcile-delay:500}")
    public void reconcile() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconciliation.reconcile();
        } finally {
            running.set(false);
        }
    }
}

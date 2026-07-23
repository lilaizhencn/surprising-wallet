package com.surprising.wallet.job.custody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.custody.repository.CustodyRepository;

@Component
public class CustodyGasReconciliationJob {
    private static final Logger log =
            LoggerFactory.getLogger(CustodyGasReconciliationJob.class);

    private final CustodyRepository repository;
    private final AtomicBoolean running = new AtomicBoolean();

    public CustodyGasReconciliationJob(CustodyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${sw.wallet.custody.gas-reconcile-delay:2000}")
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

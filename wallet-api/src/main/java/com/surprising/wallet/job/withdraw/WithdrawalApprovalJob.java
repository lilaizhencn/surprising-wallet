package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.WithdrawalApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class WithdrawalApprovalJob {
    private final WithdrawalApprovalService approvals;
    @Scheduled(scheduler="withdrawTaskScheduler",fixedDelay=5000)
    public void reconcile() { approvals.reconcile(); }
}

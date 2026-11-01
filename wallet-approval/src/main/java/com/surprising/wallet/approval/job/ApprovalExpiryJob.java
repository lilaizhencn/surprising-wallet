package com.surprising.wallet.approval.job;

import com.surprising.wallet.approval.service.ApprovalExpiryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApprovalExpiryJob {
    private final ApprovalExpiryService expiry;
    public ApprovalExpiryJob(ApprovalExpiryService expiry) { this.expiry=expiry; }
    @Scheduled(fixedDelay=5000,scheduler="approvalExpiryScheduler") public void run() { expiry.expire(); }
}

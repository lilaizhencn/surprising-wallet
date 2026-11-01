package com.surprising.wallet.job.custody;

import com.surprising.wallet.service.ApprovalMemberRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ApprovalMemberRevocationJob {
    private final ApprovalMemberRevocationService revocations;
    @Scheduled(scheduler="custodyTaskScheduler",fixedDelay=5000)
    public void reconcile(){revocations.reconcile();}
}

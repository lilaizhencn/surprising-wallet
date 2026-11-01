package com.surprising.wallet.approval.service;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.approval.repository.ApprovalRequestRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ApprovalExpiryService {
    private static final Logger LOG=LoggerFactory.getLogger(ApprovalExpiryService.class);
    private final ApprovalRequestRepository requests;
    private final ApprovalWorkflowService workflow;
    private final Clock clock;
    public ApprovalExpiryService(ApprovalRequestRepository requests,ApprovalWorkflowService workflow,Clock clock) {
        this.requests=requests;this.workflow=workflow;this.clock=clock;
    }
    public void expire() {
        for(var identity:requests.expired(clock.instant(),200)) {
            try { workflow.status(identity.tenantId(),identity.requestId()); }
            catch(RuntimeException failure) { LOG.warn("approval expiry failed for {}",identity.requestId(),failure); }
        }
    }
}

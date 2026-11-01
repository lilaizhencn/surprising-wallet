package com.surprising.wallet.service;

import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable one-way delivery; a Wallet activation can never re-enable an independently revoked reviewer. */
@Service
public class ApprovalMemberRevocationService {
    private static final Logger LOG=LoggerFactory.getLogger(ApprovalMemberRevocationService.class);
    private final CustodyTenantUserRepository users;private final WithdrawalApprovalClient authority;
    private final CustodyAuditRepository audit;private final ObjectMapper json;private final TransactionTemplate transaction;
    public ApprovalMemberRevocationService(CustodyTenantUserRepository users,WithdrawalApprovalClient authority,CustodyAuditRepository audit,ObjectMapper json,PlatformTransactionManager manager) {
        this.users=users;this.authority=authority;this.audit=audit;this.json=json;this.transaction=new TransactionTemplate(manager);
    }
    public void reconcile() {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        for(var event:users.pendingApprovalRevocations(100)) {
            if(System.nanoTime()>=deadline)return;
            try {
                var receipt=authority.revokeMember(event);
                if(!receipt.tenantId().equals(event.tenantId()) || !receipt.memberId().equals(event.memberId()) || receipt.sequence()<event.sequence()
                        || receipt.sequence()==event.sequence() && !receipt.descriptorHash().equals(event.hash()))throw new IllegalArgumentException("independent revocation receipt differs");
                transaction.executeWithoutResult(status->{
                    if(users.acknowledgeApprovalRevocation(event.tenantId(),event.memberId(),event.sequence(),receipt.sequence())==1)
                        audit.insert(UUID.randomUUID(),event.tenantId(),"SYSTEM","independent-approval","TENANT_USER.APPROVAL_REVOKED","TENANT_USER",event.memberId().toString(),null,
                            json.writeValueAsString(Map.of("sentSequence",event.sequence(),"acceptedSequence",receipt.sequence(),"descriptorHash",receipt.descriptorHash())));
                });
            } catch(RuntimeException failure) {
                if(Thread.currentThread().isInterrupted())throw failure;
                LOG.warn("Independent reviewer revocation pending: tenant={}, member={}, sequence={}",event.tenantId(),event.memberId(),event.sequence());
            }
        }
    }
}

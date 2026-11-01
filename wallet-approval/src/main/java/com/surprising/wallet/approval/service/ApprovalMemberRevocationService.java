package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.approval.ApprovalMemberRevocation;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The enrolled Wallet identity can only revoke; independent enrollment/reset/activation remain administrator-only. */
@Service
public class ApprovalMemberRevocationService {
    private final ApprovalTenantRepository tenants;private final ApprovalMemberRepository members;private final ApprovalMemberRevocationRepository revocations;
    private final ApprovalSessionRepository sessions;private final ApprovalAuditRepository audit;
    public ApprovalMemberRevocationService(ApprovalTenantRepository tenants,ApprovalMemberRepository members,ApprovalMemberRevocationRepository revocations,ApprovalSessionRepository sessions,ApprovalAuditRepository audit) {
        this.tenants=tenants;this.members=members;this.revocations=revocations;this.sessions=sessions;this.audit=audit;
    }
    @Transactional(rollbackFor=Throwable.class)
    public ApprovalMemberRevocation.Receipt revoke(ApprovalMemberRevocation event,String serviceIdentity) {
        if(!tenants.lockActive(event.tenantId()))throw new SecurityException("independent approval tenant disabled");
        var original=revocations.find(event.tenantId(),event.memberId());
        if(original.isPresent() && original.get().sequence()>=event.sequence()) {
            if(original.get().sequence()==event.sequence() && !original.get().hash().equals(event.hash()))throw new IllegalArgumentException("original revocation descriptor cannot change");
            return receipt(original.get());
        }
        revocations.advance(event);var member=members.lock(event.tenantId(),event.memberId());
        if(member.isPresent() && member.get().role().equals("APPROVER")) {
            members.disable(event.tenantId(),event.memberId());sessions.revokeMember(event.tenantId(),event.memberId());
        }
        audit.insert(event.tenantId(),"SERVICE",serviceIdentity,"MEMBER.WALLET_REVOKE",event.memberId(),Map.of("sequence",event.sequence(),"descriptorHash",event.hash(),
                "walletActorId",event.actorId(),"walletRevokedAt",event.revokedAt(),"reviewerPresent",member.filter(m->m.role().equals("APPROVER")).isPresent()));
        return receipt(event);
    }
    private static ApprovalMemberRevocation.Receipt receipt(ApprovalMemberRevocation event){return new ApprovalMemberRevocation.Receipt(event.tenantId(),event.memberId(),event.sequence(),event.hash());}
}

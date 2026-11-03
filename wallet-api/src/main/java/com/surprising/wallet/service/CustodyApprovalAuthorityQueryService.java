package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.PageView;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Tenant-scoped read of the independent authority, including authority-only test requests. */
@Service
public class CustodyApprovalAuthorityQueryService {
    private final WithdrawalApprovalClient authority;
    public CustodyApprovalAuthorityQueryService(WithdrawalApprovalClient authority) { this.authority=authority; }
    public PageView<ApprovalRequest> list(CustodyPrincipal actor,String status,int limit,int offset) {
        if(actor.actorType()!=CustodyPrincipal.ActorType.TENANT_USER || actor.tenantId()==null || !actor.hasScope("approvals:read"))
            throw new CustodyForbiddenException("Approval read permission required");
        if(!Set.of("","PENDING","APPROVED","REJECTED","EXPIRED").contains(status))
            throw new IllegalArgumentException("Invalid approval status");
        int size=Math.min(Math.max(limit,1),100),start=Math.max(offset,0);
        var page=authority.list(actor.tenantId(),status,size,start);
        return new PageView<>(page.items(),page.total(),page.limit(),page.offset());
    }
}

package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.PageView;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import com.surprising.wallet.repository.WalletWithdrawalApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Set;

/** Tenant-scoped local admission receipts. Human decisions remain on the independent approval origin. */
@Service
@RequiredArgsConstructor
public class CustodyApprovalQueryService {
    private final WalletWithdrawalApprovalRepository approvals;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageView<WithdrawalApprovalRecord> list(CustodyPrincipal actor,String state,int limit,int offset) {
        if(actor.actorType()!=CustodyPrincipal.ActorType.TENANT_USER || actor.tenantId()==null || !actor.hasScope("approvals:read")) throw new CustodyForbiddenException("Approval read permission required");
        if(!Set.of("","QUEUED","PENDING","APPROVED","AUTHORIZED","REJECTED","EXPIRED").contains(state)) throw new IllegalArgumentException("Invalid approval status");
        int size=Math.min(Math.max(limit,1),100),start=Math.max(offset,0);
        return new PageView<>(approvals.list(actor.tenantId(),state,size,start),approvals.count(actor.tenantId(),state),size,start);
    }
}

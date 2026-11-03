package com.surprising.wallet.controller;

import com.surprising.wallet.model.*;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import com.surprising.wallet.service.CustodyApprovalQueryService;
import com.surprising.wallet.service.CustodyApprovalAuthorityQueryService;
import com.surprising.wallet.common.approval.ApprovalRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/custody/console/v1/approvals")
@RequiredArgsConstructor
public class CustodyConsoleApprovalController {
    private final CustodyApprovalQueryService approvals;
    private final CustodyApprovalAuthorityQueryService authority;
    @GetMapping
    public PageView<WithdrawalApprovalRecord> list(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset,HttpServletRequest request) {
        return approvals.list(CustodyRequestSupport.requirePrincipal(request),status,limit,offset);
    }
    @GetMapping("/authority")
    public PageView<ApprovalRequest> authority(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset,HttpServletRequest request) {
        return authority.list(CustodyRequestSupport.requirePrincipal(request),status,limit,offset);
    }
}

package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.service.ApprovalMemberRevocationService;
import com.surprising.wallet.common.approval.ApprovalMemberRevocation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/member-revocations")
public class ApprovalMemberRevocationController {
    private final ApprovalMemberRevocationService revocations;
    public ApprovalMemberRevocationController(ApprovalMemberRevocationService revocations){this.revocations=revocations;}
    @PostMapping public ApprovalMemberRevocation.Receipt revoke(@RequestBody ApprovalMemberRevocation event,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))throw new SecurityException("independent service mTLS required");
        client.requireTenant(event.tenantId());return revocations.revoke(event,client.identity());
    }
}

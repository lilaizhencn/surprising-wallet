package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.service.ApprovalAutomationService;
import com.surprising.wallet.common.approval.AutomaticParentPlan;
import com.surprising.wallet.common.approval.AutomaticOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/automatic-plans")
public class ApprovalAutomaticPlanController {
    private final ApprovalAutomationService automation;
    public ApprovalAutomaticPlanController(ApprovalAutomationService automation){this.automation=automation;}
    @PostMapping public AutomaticOperation plan(@RequestBody AutomaticParentPlan plan,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))throw new SecurityException("independent service mTLS required");
        client.requireTenant(plan.tenantId());return automation.plan(plan,client.identity());
    }
}

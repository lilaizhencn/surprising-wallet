package com.surprising.wallet.approval.controller;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.model.*;
import com.surprising.wallet.approval.service.ApprovalWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/intents")
public class ApprovalIntentController {
    private final ApprovalWorkflowService workflow;
    public ApprovalIntentController(ApprovalWorkflowService workflow) { this.workflow=workflow; }
    @PostMapping public ApprovalRequest submit(@RequestBody WithdrawalIntent intent,HttpServletRequest request) {
        var client=client(request);client.requireTenant(intent.tenantId());return workflow.submit(intent,client.identity());
    }
    @GetMapping("/{id}") public ApprovalRequest status(@PathVariable UUID id,@RequestParam UUID tenantId,HttpServletRequest request) {
        client(request).requireTenant(tenantId);return workflow.status(tenantId,id);
    }
    @GetMapping public ApprovalWorkflowService.RequestPage list(@RequestParam UUID tenantId,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset,HttpServletRequest request) {
        client(request).requireTenant(tenantId);return workflow.listForService(tenantId,status,limit,offset);
    }
    private static ApprovalAuthenticationFilter.ServiceClient client(HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("approval service mTLS required");
        return client;
    }
}

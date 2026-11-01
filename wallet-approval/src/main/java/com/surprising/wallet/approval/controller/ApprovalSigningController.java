package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.service.ApprovalSigningService;
import com.surprising.wallet.common.approval.SigningCandidate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/signing-grants")
public class ApprovalSigningController {
    private final ApprovalSigningService signing;
    public ApprovalSigningController(ApprovalSigningService signing) { this.signing=signing; }
    @PostMapping public ApprovalSigningService.Outcome authorize(@RequestBody SigningCandidate candidate,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("approval service mTLS required");
        client.requireTenant(candidate.command().tenantId());
        return signing.authorize(candidate,client.identity());
    }
    @PostMapping("/monero-relay") public ApprovalSigningService.Outcome relay(@RequestBody com.surprising.wallet.common.approval.MoneroRelayCandidate candidate,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))throw new SecurityException("approval service mTLS required");
        client.requireTenant(candidate.execution().tenantId());return signing.authorizeMoneroRelay(candidate,client.identity());
    }
}

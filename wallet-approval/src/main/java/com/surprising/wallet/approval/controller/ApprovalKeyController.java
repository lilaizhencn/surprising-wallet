package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.approval.service.ApprovalKeyRegistryService;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.approval.ApprovalKeyQuarantine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/keys")
public class ApprovalKeyController {
    private final ApprovalKeyRegistryService registry;
    public ApprovalKeyController(ApprovalKeyRegistryService registry) { this.registry=registry; }
    @PostMapping public RegisteredMpcKey register(@RequestBody Registration command,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        if(command.ceremony()==null) throw new IllegalArgumentException("original key ceremony descriptor required");
        client.requireTenant(command.ceremony().tenantId());
        return registry.register(command.keyGroup(),command.ceremony(),client.identity());
    }
    @PostMapping("/quarantine")
    public ApprovalKeyQuarantine.Receipt quarantine(@RequestBody ApprovalKeyQuarantine event,
            HttpServletRequest request) {
        if (!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        client.requireTenant(event.tenantId());
        return registry.quarantine(event, client.identity());
    }
    public record Registration(String keyGroup,MpcClient.Request ceremony) { }
}

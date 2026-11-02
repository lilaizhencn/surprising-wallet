package com.surprising.wallet.approval.controller;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.model.*;
import com.surprising.wallet.approval.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/v1")
public class ApprovalConsoleController {
    private final ApprovalAuthenticationService auth;
    private final ApprovalWorkflowService workflow;
    public ApprovalConsoleController(ApprovalAuthenticationService auth,ApprovalWorkflowService workflow) { this.auth=auth;this.workflow=workflow; }
    @PostMapping("/auth/login")
    public ApprovalPrincipal login(@RequestBody Credentials credentials,HttpServletResponse response) {
        var login=auth.login(credentials.email(),credentials.password());
        if(login==null) throw new SecurityException("invalid independent approval credentials");
        response.addHeader("Set-Cookie",ResponseCookie.from(ApprovalAuthenticationFilter.COOKIE,login.token())
                .path("/").secure(true).httpOnly(true).sameSite("Strict").maxAge(java.time.Duration.ofHours(8)).build().toString());
        return login.principal();
    }
    @GetMapping("/auth/session") public ApprovalPrincipal session(HttpServletRequest request) { return principal(request); }
    @PostMapping("/auth/logout") public Map<String,Boolean> logout(HttpServletRequest request,HttpServletResponse response) {
        auth.logout(ApprovalAuthenticationFilter.token(request));
        response.addHeader("Set-Cookie",ResponseCookie.from(ApprovalAuthenticationFilter.COOKIE,"").path("/").secure(true).httpOnly(true)
                .sameSite("Strict").maxAge(0).build().toString());return Map.of("ok",true);
    }
    @GetMapping("/requests") public ApprovalWorkflowService.RequestPage requests(HttpServletRequest request,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var actor=principal(request);return workflow.list(actor.tenantId(),actor.memberId(),status,limit,offset);
    }
    @GetMapping("/requests/{id}/decisions")
    public List<ApprovalDecisionView> history(@PathVariable UUID id,HttpServletRequest request) {
        var actor=principal(request);return workflow.history(actor.tenantId(),actor.memberId(),id);
    }
    @PostMapping("/requests/{id}/decisions") public ApprovalRequest decide(@PathVariable UUID id,@RequestBody Decision command,HttpServletRequest request) {
        var actor=principal(request);return workflow.decide(actor.tenantId(),id,actor.memberId(),command.decision(),command.reason(),command.intentHash());
    }
    @GetMapping("/policies") public List<ApprovalPolicy> policies(HttpServletRequest request) {
        var actor=principal(request);return workflow.policies(actor.tenantId(),actor.memberId());
    }
    @PutMapping("/policies") public ApprovalPolicy policy(@RequestBody ApprovalWorkflowService.PolicySettings settings,HttpServletRequest request) {
        var actor=principal(request);return workflow.configure(actor.tenantId(),actor.memberId(),settings);
    }
    private static ApprovalPrincipal principal(HttpServletRequest request) {
        var actor=request.getAttribute(ApprovalAuthenticationFilter.PRINCIPAL);
        if(!(actor instanceof ApprovalPrincipal principal)) throw new SecurityException("independent approval login required");
        return principal;
    }
    public record Credentials(String email,String password) { }
    public record Decision(String decision,String reason,String intentHash) { }
}

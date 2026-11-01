package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.model.ApprovalPrincipal;
import com.surprising.wallet.approval.service.ApprovalMemberService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/v1")
public class ApprovalMemberController {
    private final ApprovalMemberService members;
    public ApprovalMemberController(ApprovalMemberService members) { this.members=members; }
    @GetMapping("/members") public ApprovalMemberService.MemberPage list(HttpServletRequest request,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var actor=principal(request);return members.list(actor.tenantId(),actor.memberId(),limit,offset);
    }
    @PostMapping("/members") public ApprovalMemberService.Activation invite(@RequestBody ApprovalMemberService.Invite input,HttpServletRequest request) {
        var actor=principal(request);return members.invite(actor.tenantId(),actor.memberId(),input);
    }
    @PostMapping("/members/{id}/activation") public ApprovalMemberService.Activation reset(@PathVariable UUID id,@RequestBody Reset input,HttpServletRequest request) {
        var actor=principal(request);return members.reset(actor.tenantId(),actor.memberId(),id,input.reason(),input.acknowledgedWalletRevocation());
    }
    @GetMapping("/members/{id}/wallet-revocation") public Map<String,Long> revocation(@PathVariable UUID id,HttpServletRequest request) {
        var actor=principal(request);return Map.of("sequence",members.revocation(actor.tenantId(),actor.memberId(),id));
    }
    @PostMapping("/members/{id}/disable") public Map<String,Boolean> disable(@PathVariable UUID id,@RequestBody Reason input,HttpServletRequest request) {
        var actor=principal(request);members.disable(actor.tenantId(),actor.memberId(),id,input.reason());return Map.of("ok",true);
    }
    @PostMapping("/auth/activate") public Map<String,Boolean> activate(@RequestBody Activation input) {
        members.activate(input.token(),input.password());return Map.of("ok",true);
    }
    @GetMapping("/audits") public ApprovalMemberService.AuditPage audits(HttpServletRequest request,@RequestParam(defaultValue="") String action,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var actor=principal(request);return members.audits(actor.tenantId(),actor.memberId(),action,limit,offset);
    }
    private static ApprovalPrincipal principal(HttpServletRequest request) {
        if(request.getAttribute(ApprovalAuthenticationFilter.PRINCIPAL) instanceof ApprovalPrincipal actor)return actor;
        throw new SecurityException("independent approval login required");
    }
    public record Reason(String reason) { }
    public record Reset(String reason,long acknowledgedWalletRevocation) { }
    public record Activation(String token,String password) { }
}

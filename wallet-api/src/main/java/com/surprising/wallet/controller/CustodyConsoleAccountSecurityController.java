package com.surprising.wallet.controller;

import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.model.CustodySessionCookie;
import com.surprising.wallet.service.CustodyLoginSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/custody/console/v1")
@RequiredArgsConstructor
public class CustodyConsoleAccountSecurityController {
    private final CustodyLoginSecurityService security;

    @GetMapping("/account-security")
    public CustodyLoginSecurityService.SecurityView own(HttpServletRequest r) { return security.own(CustodyRequestSupport.requirePrincipal(r)); }
    @GetMapping("/users/security")
    public List<CustodyLoginSecurityService.SecurityView> list(HttpServletRequest r) { return security.list(CustodyRequestSupport.requirePrincipal(r)); }
    @PutMapping("/users/{id}/login-policy")
    public CustodyLoginSecurityService.SecurityView policy(@PathVariable UUID id,@RequestBody Policy body,HttpServletRequest r) {
        return security.policy(CustodyRequestSupport.requirePrincipal(r),id,body.emailRequired(),body.totpRequired(),CustodyRequestSupport.clientIp(r));
    }
    @PostMapping("/users/{id}/reset-totp")
    public Map<String,Boolean> reset(@PathVariable UUID id,@RequestBody Reason body,HttpServletRequest r) {
        security.reset(CustodyRequestSupport.requirePrincipal(r),id,body.reason(),CustodyRequestSupport.clientIp(r)); return Map.of("ok",true);
    }
    @PostMapping("/account-security/totp/setup")
    public CustodyLoginSecurityService.Setup setup(@RequestBody Credentials body,HttpServletRequest r) {
        return security.setup(CustodySessionCookie.read(r.getCookies()),body.password(),true,CustodyRequestSupport.clientIp(r));
    }
    @PostMapping("/account-security/totp/confirm")
    public CustodyLoginSecurityService.SecurityView confirm(@RequestBody Credentials body,HttpServletRequest r) {
        return security.confirm(CustodySessionCookie.read(r.getCookies()),body.code(),CustodyRequestSupport.clientIp(r));
    }
    @PostMapping("/account-security/totp/unbind")
    public Map<String,Boolean> unbind(@RequestBody Credentials body,HttpServletRequest r) {
        security.unbind(CustodySessionCookie.read(r.getCookies()),body.password(),body.code(),CustodyRequestSupport.clientIp(r)); return Map.of("ok",true);
    }
    public record Policy(boolean emailRequired,boolean totpRequired) { }
    public record Reason(String reason) { }
    public record Credentials(String password,String code) { }
}

package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/custody/console/v1/auth")
public class CustodyConsoleAuthController {
    private final CustodyAuthService auth;

    public CustodyConsoleAuthController(CustodyAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public CustodyAuthService.LoginResult login(@RequestBody TenantLoginRequest body,
                                                HttpServletRequest request) {
        return auth.tenantLogin(body.tenantSlug(), body.email(), body.password(),
                CustodyRequestSupport.clientIp(request), request.getHeader("User-Agent"));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        return Map.of(
                "userId", principal.actorId(),
                "tenantId", principal.tenantId(),
                "tenantSlug", principal.tenantSlug(),
                "role", principal.role(),
                "scopes", principal.scopes());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        auth.logout(request);
        return Map.of("ok", true);
    }

    public record TenantLoginRequest(String tenantSlug, String email, String password) {
    }
}

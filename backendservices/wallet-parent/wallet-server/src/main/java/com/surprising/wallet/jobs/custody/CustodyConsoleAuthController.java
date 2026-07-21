package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        CustodyAuthService.LoginResult result = auth.tenantLogin(body.tenantSlug(), body.email(), body.password(),
                CustodyRequestSupport.clientIp(request), request.getHeader("User-Agent"));
        CustodySessionCookie.set(response, result.token(),
                java.time.Duration.between(java.time.Instant.now(), result.expiresAt()),
                auth.sessionCookieSecure());
        return result;
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
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request);
        CustodySessionCookie.clear(response, auth.sessionCookieSecure());
        return Map.of("ok", true);
    }

    public record TenantLoginRequest(String tenantSlug, String email, String password) {
    }
}

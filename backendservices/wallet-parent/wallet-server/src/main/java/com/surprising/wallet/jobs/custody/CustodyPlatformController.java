package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyTenantService.CreateTenantCommand;
import com.surprising.wallet.jobs.custody.CustodyTenantService.TenantDetail;
import com.surprising.wallet.jobs.custody.CustodyTenantService.TenantPage;
import com.surprising.wallet.jobs.custody.CustodyTenantService.UpdateTenantCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/custody/platform/v1")
public class CustodyPlatformController {
    private final CustodyAuthService auth;
    private final CustodyTenantService tenants;

    public CustodyPlatformController(CustodyAuthService auth, CustodyTenantService tenants) {
        this.auth = auth;
        this.tenants = tenants;
    }

    @PostMapping("/auth/login")
    public CustodyAuthService.LoginResult login(@RequestBody PlatformLoginRequest body,
                                                HttpServletRequest request) {
        return auth.platformLogin(body.email(), body.password(),
                CustodyRequestSupport.clientIp(request), request.getHeader("User-Agent"));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        return Map.of(
                "userId", principal.actorId(),
                "role", principal.role(),
                "scopes", principal.scopes());
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        auth.logout(request);
        return Map.of("ok", true);
    }

    @GetMapping("/tenants")
    public TenantPage tenants(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return tenants.list(
                CustodyRequestSupport.requirePrincipal(request),
                search, status, limit, offset);
    }

    @PostMapping("/tenants")
    public CustodyRepository.TenantRecord createTenant(@RequestBody CreateTenantCommand body,
                                                        HttpServletRequest request) {
        return tenants.create(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantDetail tenant(@PathVariable UUID tenantId,
                               HttpServletRequest request) {
        return tenants.detail(CustodyRequestSupport.requirePrincipal(request), tenantId);
    }

    @PatchMapping("/tenants/{tenantId}")
    public CustodyRepository.TenantRecord updateTenant(
            @PathVariable UUID tenantId,
            @RequestBody UpdateTenantCommand body,
            HttpServletRequest request) {
        return tenants.update(
                CustodyRequestSupport.requirePrincipal(request),
                tenantId,
                body,
                CustodyRequestSupport.clientIp(request));
    }

    @PatchMapping("/tenants/{tenantId}/status")
    public CustodyRepository.TenantRecord status(@PathVariable UUID tenantId,
                                                 @RequestBody TenantStatusRequest body,
                                                 HttpServletRequest request) {
        return tenants.updateStatus(CustodyRequestSupport.requirePrincipal(request), tenantId,
                body.status(), CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/tenants/{tenantId}/administrators/{userId}/unlock")
    public Map<String, Object> unlockAdministrator(
            @PathVariable UUID tenantId,
            @PathVariable UUID userId,
            HttpServletRequest request) {
        return tenants.unlockAdministrator(
                CustodyRequestSupport.requirePrincipal(request),
                tenantId,
                userId,
                CustodyRequestSupport.clientIp(request));
    }

    public record PlatformLoginRequest(String email, String password) {
    }

    public record TenantStatusRequest(String status) {
    }
}

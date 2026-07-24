package com.surprising.wallet.custody.controller.platform;

import com.surprising.wallet.custody.service.CustodyTenantService.CreateTenantCommand;
import com.surprising.wallet.custody.service.CustodyTenantService.TenantDetail;
import com.surprising.wallet.custody.service.CustodyTenantService.TenantPage;
import com.surprising.wallet.custody.service.CustodyTenantService.UpdateTenantCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.repository.CustodyAssetDashboardRepository;
import com.surprising.wallet.custody.service.CustodyAssetDashboardService;
import com.surprising.wallet.custody.service.CustodyAuthService;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.model.CustodySessionCookie;
import com.surprising.wallet.custody.service.CustodyTenantService;

@RestController
@RequestMapping("/custody/platform/v1")
public class CustodyPlatformController {
    /** 平台鉴权服务。 */
    private final CustodyAuthService auth;
    /** 平台租户管理服务。 */
    private final CustodyTenantService tenants;
    /** 平台资产仪表盘服务。 */
    private final CustodyAssetDashboardService assets;

    /**
     * 注入平台鉴权、租户与资产服务。
     */
    public CustodyPlatformController(CustodyAuthService auth, CustodyTenantService tenants,
                                     CustodyAssetDashboardService assets) {
        this.auth = auth;
        this.tenants = tenants;
        this.assets = assets;
    }

    /**
     * 管理员登录，签发会话 token 并返回。
     */
    @PostMapping("/auth/login")
    public CustodyAuthService.LoginResult login(@RequestBody PlatformLoginRequest body,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        CustodyAuthService.LoginResult result = auth.platformLogin(body.email(), body.password(),
                CustodyRequestSupport.clientIp(request), request.getHeader("User-Agent"));
        CustodySessionCookie.set(response, result.token(),
                java.time.Duration.between(java.time.Instant.now(), result.expiresAt()),
                auth.sessionCookieSecure());
        return result;
    }

    /**
     * 当前会话身份信息。
     */
    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        return Map.of(
                "userId", principal.actorId(),
                "role", principal.role(),
                "scopes", principal.scopes());
    }

    /**
     * 清理登录会话并返回成功。
     */
    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request);
        CustodySessionCookie.clear(response, auth.sessionCookieSecure());
        return Map.of("ok", true);
    }

    /**
     * 分页查询租户列表。
     */
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

    /**
     * 创建新租户。
     */
    @PostMapping("/tenants")
    public CustodyRepository.TenantRecord createTenant(@RequestBody CreateTenantCommand body,
                                                        HttpServletRequest request) {
        return tenants.create(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 查询租户详情。
     */
    @GetMapping("/tenants/{tenantId}")
    public TenantDetail tenant(@PathVariable UUID tenantId,
                               HttpServletRequest request) {
        return tenants.detail(CustodyRequestSupport.requirePrincipal(request), tenantId);
    }

    /**
     * 更新租户基本信息。
     */
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

    /**
     * 更新租户状态（启用/禁用）。
     */
    @PatchMapping("/tenants/{tenantId}/status")
    public CustodyRepository.TenantRecord status(@PathVariable UUID tenantId,
                                                 @RequestBody TenantStatusRequest body,
                                                 HttpServletRequest request) {
        return tenants.updateStatus(CustodyRequestSupport.requirePrincipal(request), tenantId,
                body.status(), CustodyRequestSupport.clientIp(request));
    }

    /**
     * 解锁租户管理员账号。
     */
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

    /**
     * 查询资产价格设置。
     */
    @GetMapping("/asset-prices")
    public java.util.List<CustodyAssetDashboardRepository.AssetPrice> assetPrices(
            HttpServletRequest request) {
        return assets.prices(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 更新资产价格快照。
     */
    @PutMapping("/asset-prices/{symbol}")
    public CustodyAssetDashboardRepository.AssetPrice setAssetPrice(
            @PathVariable String symbol,
            @RequestBody CustodyAssetDashboardService.SetPriceCommand body,
            HttpServletRequest request) {
        return assets.setPrice(CustodyRequestSupport.requirePrincipal(request), symbol, body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 平台登录请求体。
     */
    public record PlatformLoginRequest(String email, String password) {
    }

    /**
     * 租户状态更新请求体。
     */
    public record TenantStatusRequest(String status) {
    }
}

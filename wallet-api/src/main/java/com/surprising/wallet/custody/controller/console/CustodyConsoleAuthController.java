package com.surprising.wallet.custody.controller.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import com.surprising.wallet.custody.service.CustodyAuthService;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.model.CustodySessionCookie;

/**
 * Console 认证控制器。
 *
 * <p>端点路径：/custody/console/v1/auth。提供登录（POST /login）、
 * 登出（POST /logout）、当前用户信息查询（GET /me）功能。
 */
@RestController
@RequestMapping("/custody/console/v1/auth")
public class CustodyConsoleAuthController {
    /** 控制台鉴权服务，提供租户登录、登出、会话管理。 */
    private final CustodyAuthService auth;

    /**
     * 注入鉴权服务。
     */
    public CustodyConsoleAuthController(CustodyAuthService auth) {
        this.auth = auth;
    }

    /**
     * 处理租户控制台登录，返回 token 并写入会话 cookie。
     */
    @PostMapping("/login")
    public CustodyAuthService.LoginResult login(@RequestBody TenantLoginRequest body,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        CustodyAuthService.LoginResult result = auth.tenantLogin(body.email(), body.password(),
                CustodyRequestSupport.clientIp(request), request.getHeader("User-Agent"));
        CustodySessionCookie.set(response, result.token(),
                java.time.Duration.between(java.time.Instant.now(), result.expiresAt()),
                auth.sessionCookieSecure());
        return result;
    }

    /**
     * 返回当前会话的租户身份信息。
     */
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

    /**
     * 清理当前会话并退出登录。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request);
        CustodySessionCookie.clear(response, auth.sessionCookieSecure());
        return Map.of("ok", true);
    }

    /**
     * 登录请求体：用户名与密码。
     */
    public record TenantLoginRequest(String email, String password) {
    }
}

package com.surprising.wallet.custody.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import com.surprising.wallet.custody.service.CustodyAuthService;
import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyHttpErrors;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CustodyConsoleAuthenticationFilter extends OncePerRequestFilter {
    /** 会话鉴权服务。 */
    private final CustodyAuthService auth;
    /** 错误响应序列化工具。 */
    private final ObjectMapper objectMapper;

    /**
     * 控制台/平台路由复用同一拦截器，通过 URI 判断业务域。
     */
    public CustodyConsoleAuthenticationFilter(CustodyAuthService auth, ObjectMapper objectMapper) {
        this.auth = auth;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅拦截 console 与 platform API，登录接口与 OPTIONS 不做拦截。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        boolean console = path.startsWith("/custody/console/v1/");
        boolean platform = path.startsWith("/custody/platform/v1/");
        return (!console && !platform) || path.endsWith("/auth/login");
    }

    /**
     * 根据路径识别 platform 与 console 会话，校验 session 并注入 Principal。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            boolean platform = request.getRequestURI().startsWith("/custody/platform/v1/");
            CustodyPrincipal principal = auth.requireSession(request, platform);
            request.setAttribute(CustodyRequestSupport.PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (CustodyForbiddenException e) {
            CustodyHttpErrors.write(objectMapper, response, 403, "FORBIDDEN", e.getMessage());
        } catch (CustodyUnauthorizedException e) {
            CustodyHttpErrors.write(objectMapper, response, 401, "UNAUTHORIZED", e.getMessage());
        }
    }
}

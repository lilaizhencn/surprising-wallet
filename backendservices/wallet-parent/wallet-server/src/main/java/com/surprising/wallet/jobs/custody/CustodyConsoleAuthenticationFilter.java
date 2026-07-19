package com.surprising.wallet.jobs.custody;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CustodyConsoleAuthenticationFilter extends OncePerRequestFilter {
    private final CustodyAuthService auth;
    private final ObjectMapper objectMapper;

    public CustodyConsoleAuthenticationFilter(CustodyAuthService auth, ObjectMapper objectMapper) {
        this.auth = auth;
        this.objectMapper = objectMapper;
    }

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

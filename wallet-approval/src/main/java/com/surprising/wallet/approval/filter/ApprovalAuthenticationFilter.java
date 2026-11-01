package com.surprising.wallet.approval.filter;

import com.surprising.wallet.approval.config.ApprovalProperties;
import com.surprising.wallet.approval.service.ApprovalAuthenticationService;
import com.surprising.wallet.common.approval.SigningAuthorization;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class ApprovalAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE="__Host-sw_approval", PRINCIPAL="approval.principal", CLIENT="approval.client";
    private final ApprovalAuthenticationService auth;
    private final ApprovalProperties properties;
    private final ObjectMapper json;
    public ApprovalAuthenticationFilter(ApprovalAuthenticationService auth,ApprovalProperties properties,ObjectMapper json) {
        this.auth=auth;this.properties=properties;this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        try {
            if(!request.isSecure()) throw new SecurityException("approval HTTPS required");
            var path=request.getRequestURI();
            if(path.startsWith("/approval/service/v1/")) {
                var certificates=(X509Certificate[])request.getAttribute("jakarta.servlet.request.X509Certificate");
                if(certificates==null || certificates.length==0) throw new SecurityException("independent service mTLS required");
                var identity=SigningAuthorization.hash(certificates[0].getPublicKey().getEncoded());
                var tenants=properties.serviceClients().get(identity);
                if(tenants==null) throw new SecurityException("approval service identity not enrolled");
                request.setAttribute(CLIENT,new ServiceClient(identity,tenants));
            } else if(path.startsWith("/approval/v1/")) {
                boolean mutation=!Set.of("GET","HEAD").contains(request.getMethod());
                if(mutation && !properties.publicOrigin().equals(request.getHeader("Origin")))
                    throw new SecurityException("approval request origin differs");
                boolean login=Set.of("/approval/v1/auth/login","/approval/v1/auth/activate").contains(path) && request.getMethod().equals("POST");
                if(!login) {
                    var principal=auth.require(token(request));
                    if(mutation) {
                        var provided=request.getHeader("X-Approval-CSRF");
                        if(provided==null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),principal.csrfToken().getBytes(StandardCharsets.UTF_8)))
                            throw new SecurityException("approval CSRF token differs");
                    }
                    request.setAttribute(PRINCIPAL,principal);
                }
            } else { response.setStatus(404);return; }
            chain.doFilter(request,response);
        } catch(SecurityException error) {
            response.setStatus(401);response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(Map.of("code","APPROVAL_AUTH_REQUIRED","message",error.getMessage())));
        }
    }
    public static String token(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals(COOKIE)) return cookie.getValue();
        return null;
    }
    public record ServiceClient(String identity,Set<UUID> tenants) {
        public ServiceClient { tenants=Set.copyOf(tenants); }
        public void requireTenant(UUID tenant) { if(!tenants.contains(tenant)) throw new SecurityException("approval service tenant differs"); }
    }
}

package com.surprising.wallet.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Enforce exact browser origins even for the restricted, pre-authentication endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 29)
public class CustodyBrowserSecurityFilter extends OncePerRequestFilter {
    private final Set<String> origins;
    public CustodyBrowserSecurityFilter(@Value("${SW_CUSTODY_CORS_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        this.origins=new HashSet<>(Arrays.stream(origins.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList());
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/custody/console/v1/") && !r.getRequestURI().startsWith("/custody/platform/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
        s.setHeader("Cache-Control","no-store");
        String origin=r.getHeader("Origin");
        if(!Set.of("GET","HEAD","OPTIONS").contains(r.getMethod()) &&
                ((origin!=null && !origins.contains(origin)) || "cross-site".equals(r.getHeader("Sec-Fetch-Site")) && origin==null)) {
            s.sendError(403,"Untrusted browser origin"); return;
        }
        chain.doFilter(r,s);
    }
}

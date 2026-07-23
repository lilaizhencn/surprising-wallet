package com.surprising.wallet.custody.model;

import jakarta.servlet.http.HttpServletRequest;

import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

public final class CustodyRequestSupport {
    public static final String PRINCIPAL_ATTRIBUTE =
            CustodyRequestSupport.class.getName() + ".principal";

    private CustodyRequestSupport() {
    }

    public static String clientIp(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        return value == null ? "" : value.trim();
    }

    public static CustodyPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof CustodyPrincipal principal) {
            return principal;
        }
        throw new CustodyUnauthorizedException("authentication required");
    }
}

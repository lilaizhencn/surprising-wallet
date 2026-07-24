package com.surprising.wallet.custody.model;

import jakarta.servlet.http.HttpServletRequest;

import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

public final class CustodyRequestSupport {
    /** 请求上下文中存储鉴权结果时的属性名。 */
    public static final String PRINCIPAL_ATTRIBUTE =
            CustodyRequestSupport.class.getName() + ".principal";

    private CustodyRequestSupport() {
    }

    /**
     * 读取远端地址，并去除两端空白，供审计与日志记录。
     */
    public static String clientIp(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        return value == null ? "" : value.trim();
    }

    /**
     * 从请求属性里取出已鉴权主体；缺失直接抛未授权异常。
     */
    public static CustodyPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof CustodyPrincipal principal) {
            return principal;
        }
        throw new CustodyUnauthorizedException("authentication required");
    }
}

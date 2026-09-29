package com.surprising.wallet.model;

import jakarta.servlet.http.HttpServletRequest;

import com.surprising.wallet.exception.CustodyUnauthorizedException;

/**
 * 托管请求工具类，提供从 {@link HttpServletRequest} 中提取客户端 IP
 * 以及读取已鉴权托管主体（{@link CustodyPrincipal}）的静态方法。
 *
 * <p>认证 Filter 将解析后的主体存入请求属性，Controller 通过 {@link #requirePrincipal(HttpServletRequest)} 获取。
 */
public final class CustodyRequestSupport {
    /** 请求上下文中存储鉴权结果时的属性名。 */
    public static final String PRINCIPAL_ATTRIBUTE =
            CustodyRequestSupport.class.getName() + ".principal";

    /**
     * 构造 {@code CustodyRequestSupport}，初始化该组件运行所需的状态和依赖。
     */
    private CustodyRequestSupport() {
    }

    /**
     * 读取容器解析的远端地址，移除 IPv6 URI 方括号，供 inet 字段和 IP 白名单使用。
     * 不直接信任客户端提供的转发头。
     */
    public static String clientIp(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        if (value == null) {
            return "";
        }
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]") && address.contains(":")) {
            return address.substring(1, address.length() - 1);
        }
        return address;
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

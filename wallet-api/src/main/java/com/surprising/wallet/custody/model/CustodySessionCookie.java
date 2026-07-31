package com.surprising.wallet.custody.model;

import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Custody Console 会话 Cookie 工具类。
 *
 * <p>管理 Console 用户的认证会话 Cookie，提供读取（从请求中提取 token）、
 * 设置（写入 Set-Cookie 响应头）和清除（设置 maxAge=0）功能。
 * Cookie 属性：HttpOnly、SameSite=Lax、path=/custody、Secure（由配置决定）。
 */
public final class CustodySessionCookie {
    /**
     * 定义 {@code NAME} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String NAME = "SW_CUSTODY_SESSION";

    /**
     * 构造 {@code CustodySessionCookie}，初始化该组件运行所需的状态和依赖。
     */
    private CustodySessionCookie() {
    }

    /**
     * 获取或查询 {@code read} 对应的数据，供调用方读取当前状态。
     */
    public static String read(Cookie[] cookies) {
        if (cookies == null) {
            throw new CustodyUnauthorizedException("session cookie required");
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && cookie.getValue().startsWith("cs_") && cookie.getValue().length() >= 32) {
                return cookie.getValue();
            }
        }
        throw new CustodyUnauthorizedException("session cookie required");
    }

    /**
     * 设置或更新 {@code set} 对应的状态，并保持相关业务字段一致。
     */
    public static void set(HttpServletResponse response, String token, Duration ttl, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, ttl, secure).toString());
    }

    /**
     * 删除或清理 {@code clear} 对应的数据，并处理相关状态收敛。
     */
    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure).toString());
    }

    /**
     * 执行 {@code cookie} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static ResponseCookie cookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/custody")
                .maxAge(maxAge)
                .build();
    }
}

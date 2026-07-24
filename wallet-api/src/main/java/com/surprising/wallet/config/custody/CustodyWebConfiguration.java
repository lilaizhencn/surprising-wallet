package com.surprising.wallet.config.custody;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Custody 模块 CORS 跨域配置。
 *
 * <p>配置 /custody/** 路径的 CORS 策略，允许的来源通过 {@code SW_CUSTODY_CORS_ORIGINS}
 * 环境变量指定（逗号分隔），支持 Console 前端和 API 客户端的跨域请求。
 * 同时暴露认证所需的请求头（X-Custody-Key、X-Custody-Signature 等）和 Location 响应头。
 */
@Configuration
public class CustodyWebConfiguration implements WebMvcConfigurer {
    /** 允许跨域来源白名单，默认开发端口。 */
    private final String[] allowedOrigins;

    /**
     * 解析 CORS 环境变量，支持逗号分隔多源配置。
     *
     * @param allowedOrigins 配置值，如 http://127.0.0.1:5173,...
     */
    public CustodyWebConfiguration(
            @Value("${SW_CUSTODY_CORS_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }

    /**
     * 配置 custody API 的跨域白名单与允许头部/返回头，支持会话签名字段。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/custody/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders(
                        "Authorization", "Content-Type", "Idempotency-Key",
                        "X-Custody-Key", "X-Custody-Timestamp", "X-Custody-Nonce",
                        "X-Custody-Signature")
                .exposedHeaders("Location", "X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

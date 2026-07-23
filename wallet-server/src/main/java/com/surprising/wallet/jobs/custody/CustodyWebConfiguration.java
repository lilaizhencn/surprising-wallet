package com.surprising.wallet.jobs.custody;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CustodyWebConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public CustodyWebConfiguration(
            @Value("${SW_CUSTODY_CORS_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }

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

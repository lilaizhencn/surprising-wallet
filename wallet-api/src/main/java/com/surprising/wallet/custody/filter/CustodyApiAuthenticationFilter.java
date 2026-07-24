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

import com.surprising.wallet.config.custody.CachedBodyHttpServletRequest;
import com.surprising.wallet.custody.service.CustodyApiKeyService;
import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyHttpErrors;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CustodyApiAuthenticationFilter extends OncePerRequestFilter {
    /** API 请求体最大允许字节，超出直接拒绝。 */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** API Key 鉴权服务：负责签名验签与防重放。 */
    private final CustodyApiKeyService apiKeys;
    /** 错误响应序列化工具。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造注入服务与 JSON 编码器，避免单例内部静态查找。
     */
    public CustodyApiAuthenticationFilter(CustodyApiKeyService apiKeys, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅处理 custody API 请求路径，排除 CORS 预检请求。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/custody/api/v1/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    /**
     * 读取并验证签名头、时间戳与 body，鉴权通过后写入 Principal。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            long contentLength = request.getContentLengthLong();
            if (contentLength > MAX_BODY_BYTES) {
                CustodyHttpErrors.write(objectMapper, response, 413,
                        "PAYLOAD_TOO_LARGE", "request body exceeds 2 MiB");
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                CustodyHttpErrors.write(objectMapper, response, 413,
                        "PAYLOAD_TOO_LARGE", "request body exceeds 2 MiB");
                return;
            }
            String keyId = requiredHeader(request, "X-Custody-Key");
            String nonce = requiredHeader(request, "X-Custody-Nonce");
            String signature = requiredHeader(request, "X-Custody-Signature");
            long timestamp = parseTimestamp(requiredHeader(request, "X-Custody-Timestamp"));
            String requestTarget = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            CustodyPrincipal principal = apiKeys.authenticate(
                    keyId, timestamp, nonce, signature, request.getMethod(),
                    requestTarget, body, CustodyRequestSupport.clientIp(request));
            CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);
            wrapped.setAttribute(CustodyRequestSupport.PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(wrapped, response);
        } catch (CustodyForbiddenException e) {
            CustodyHttpErrors.write(objectMapper, response, 403, "FORBIDDEN", e.getMessage());
        } catch (CustodyUnauthorizedException | IllegalArgumentException e) {
            CustodyHttpErrors.write(objectMapper, response, 401, "UNAUTHORIZED", e.getMessage());
        }
    }

    /**
     * 读取固定 Header，缺失时直接抛出未授权异常。
     */
    private static String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new CustodyUnauthorizedException("missing " + name + " header");
        }
        return value.trim();
    }

    /**
     * 解析时间戳头，非法值视为未授权。
     */
    private static long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new CustodyUnauthorizedException("invalid X-Custody-Timestamp header");
        }
    }
}

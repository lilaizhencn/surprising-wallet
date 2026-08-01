package com.surprising.wallet.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.repository.CustodyRepository.WebhookEndpointRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;

/**
 * 托管 Webhook 投递服务，负责向租户配置的 Webhook 端点投递事件通知。
 *
 * <p>支持的事件类型：充值到账（deposit.credited）、提现状态变更（withdrawal.*）。
 * 每次投递使用 HMAC-SHA256 签名，租户可验证来源。失败后会按重试策略自动重试，
 * 所有投递历史记录在 webhook_delivery 表中用于审计和手动重放。
 */
@Service
public class CustodyWebhookService {
    /**
     * 定义 {@code DELIVERY_STATUSES} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final java.util.Set<String> DELIVERY_STATUSES = java.util.Set.of(
            "PENDING", "DELIVERING", "DELIVERED", "RETRY", "FAILED");
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository repository;
    /**
     * 保存 {@code crypto}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodyCryptoService crypto;
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper;
    /**
     * 保存 {@code httpClient}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final HttpClient httpClient;
    /**
     * 保存 {@code production}，用于承载当前对象的运行配置或业务数据。
     */
    private final boolean production;

    /**
     * 构造 {@code CustodyWebhookService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyWebhookService(CustodyRepository repository,
                                 CustodyCryptoService crypto,
                                 ObjectMapper objectMapper,
                                 @Value("${sw.app.env.name:dev}")
                                 String environment) {
        this.repository = repository;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.production = "prod".equalsIgnoreCase(environment)
                || "production".equalsIgnoreCase(environment);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 构建或生成 {@code create} 对应的结果，并执行输入和状态校验。
     */
    @Transactional(rollbackFor = Throwable.class)
    public CreatedWebhook create(CustodyPrincipal principal, CreateWebhookCommand command, String sourceIp) {
        requireTenantAdmin(principal);
        String name = required(command.name(), "webhook name", 120);
        URI uri = validateEndpoint(command.url());
        String secret = "whsec_" + crypto.randomSecret(32);
        String verificationToken = crypto.randomSecret(24);
        UUID id = UUID.randomUUID();
        WebhookEndpointRecord saved = repository.insertWebhookEndpoint(
                id, principal.tenantId(), name, uri.toString(), crypto.encrypt(secret),
                crypto.sha256(verificationToken), principal.actorId());
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.CREATE", "WEBHOOK_ENDPOINT", id.toString(), sourceIp,
                json(Map.of("url", uri.toString())));
        return new CreatedWebhook(
                saved.id(), saved.name(), saved.url(), saved.status(),
                secret, saved.createdAt());
    }
    /**
     * 获取或查询 {@code list} 对应的数据，供调用方读取当前状态。
     */
    public List<Map<String, Object>> list(CustodyPrincipal principal) {
        requireScope(principal, "webhooks:read");
        return repository.listWebhookEndpoints(principal.tenantId());
    }

    /**
     * 验证 {@code verify} 对应的签名、交易或数据证明是否有效。
     */
    @Transactional(rollbackFor = Throwable.class)
    public WebhookEndpointRecord verify(CustodyPrincipal principal, UUID endpointId, String sourceIp) {
        requireTenantAdmin(principal);
        WebhookEndpointRecord endpoint = repository.requireWebhookEndpoint(principal.tenantId(), endpointId);
        if (!"PENDING_VERIFICATION".equals(endpoint.status())) {
            throw new IllegalStateException("only pending webhook endpoints can be verified");
        }
        String challenge = crypto.randomSecret(24);
        UUID eventId = UUID.randomUUID();
        String body = json(Map.of(
                "id", eventId,
                "type", "WEBHOOK.VERIFICATION",
                "createdAt", Instant.now(),
                "data", Map.of("challenge", challenge)));
        WebhookHttpResult result = send(
                endpoint.url(), crypto.decrypt(endpoint.secretCiphertext()),
                eventId, "WEBHOOK.VERIFICATION", body);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new IllegalStateException(
                    "webhook verification returned HTTP " + result.statusCode());
        }
        try {
            JsonNode response = objectMapper.readTree(result.body());
            if (!challenge.equals(response.path("challenge").asText())) {
                throw new IllegalStateException("webhook verification response did not echo the challenge");
            }
        } catch (JacksonException e) {
            throw new IllegalStateException("webhook verification response must be valid JSON", e);
        }
        repository.markWebhookVerified(principal.tenantId(), endpointId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.VERIFY", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp, "{}");
        return repository.requireWebhookEndpoint(principal.tenantId(), endpointId);
    }

    /**
     * 设置或更新 {@code setEnabled} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void setEnabled(CustodyPrincipal principal, UUID endpointId, boolean enabled, String sourceIp) {
        requireTenantAdmin(principal);
        WebhookEndpointRecord endpoint = repository.requireWebhookEndpoint(principal.tenantId(), endpointId);
        if (enabled && endpoint.verifiedAt() == null) {
            throw new IllegalStateException("verify the webhook endpoint before enabling it");
        }
        String status = enabled ? "ACTIVE" : "DISABLED";
        repository.setWebhookStatus(principal.tenantId(), endpointId, status);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.STATUS_CHANGE", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp,
                "{\"status\":\"" + status + "\"}");
    }

    /**
     * 执行 {@code deliveries} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<Map<String, Object>> deliveries(CustodyPrincipal principal, UUID endpointId,
                                                String status, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        String normalizedStatus = normalizeDeliveryStatus(status);
        return repository.listWebhookDeliveries(
                principal.tenantId(), endpointId, normalizedStatus, limit, offset);
    }

    /**
     * 执行 {@code deliveryAttempts} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<Map<String, Object>> deliveryAttempts(
            CustodyPrincipal principal, UUID deliveryId, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        if (deliveryId == null) {
            throw new IllegalArgumentException("deliveryId is required");
        }
        return repository.listWebhookDeliveryAttempts(
                principal.tenantId(), deliveryId, limit, offset);
    }

    /**
     * 处理 {@code retry} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void retry(CustodyPrincipal principal, UUID deliveryId, String sourceIp) {
        requireTenantAdmin(principal);
        repository.retryWebhookDelivery(principal.tenantId(), deliveryId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.DELIVERY_RETRY", "WEBHOOK_DELIVERY", deliveryId.toString(), sourceIp, "{}");
    }

    /**
     * 处理 {@code retryFailed} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public int retryFailed(CustodyPrincipal principal, UUID endpointId, String sourceIp) {
        requireTenantAdmin(principal);
        if (endpointId == null) {
            throw new IllegalArgumentException("endpointId is required");
        }
        repository.requireWebhookEndpoint(principal.tenantId(), endpointId);
        int queued = repository.retryFailedWebhookDeliveries(principal.tenantId(), endpointId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.DELIVERY_RETRY_BATCH", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp,
                json(Map.of("queued", queued, "statuses", List.of("FAILED", "RETRY"))));
        return queued;
    }
    /**
     * 转换或计算 {@code normalizeDeliveryStatus} 对应的值，统一金额、格式和边界规则。
     */
    private String normalizeDeliveryStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (!DELIVERY_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported webhook delivery status");
        }
        return normalized;
    }

    /**
     * 发送或广播 {@code send} 对应的链上请求，并返回节点处理结果。
     */
    public WebhookHttpResult send(String url, String secret, UUID eventId,
                                  String eventType, String body) {
        URI uri = validateEndpoint(url);
        long timestamp = Instant.now().getEpochSecond();
        String signature = crypto.hmacSha256(secret, timestamp + "." + body);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Surprising-Wallet-Webhook/1.0")
                .header("X-Custody-Event-Id", eventId.toString())
                .header("X-Custody-Event-Type", eventType)
                .header("X-Custody-Timestamp", Long.toString(timestamp))
                .header("X-Custody-Signature", "v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(4097);
                String responseBody = new String(
                        bytes, 0, Math.min(bytes.length, 4096), java.nio.charset.StandardCharsets.UTF_8);
                return new WebhookHttpResult(
                        response.statusCode(),
                        responseBody,
                        response.headers().firstValue("Retry-After").orElse(null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webhook request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("webhook request failed: " + e.getMessage(), e);
        }
    }
    /**
     * 校验 {@code validateEndpoint} 对应的前置条件，不满足时抛出明确异常。
     */
    URI validateEndpoint(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isBlank() || url.length() > 2048) {
            throw new IllegalArgumentException("valid webhook URL is required");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !(!production && "http".equals(scheme))) {
                throw new IllegalArgumentException("webhook URL must use HTTPS");
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("webhook URL must not contain credentials or a fragment");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                boolean local = address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress();
                boolean permittedDevLoopback = !production && address.isLoopbackAddress();
                if (local && !permittedDevLoopback) {
                    throw new IllegalArgumentException(
                            "webhook URL must not resolve to a private or link-local address");
                }
            }
            return uri;
        } catch (URISyntaxException | IOException e) {
            throw new IllegalArgumentException("webhook URL is invalid or cannot be resolved", e);
        }
    }
    /**
     * 编码 {@code json} 对应的数据，生成链上或接口所需的表示。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize webhook payload", e);
        }
    }
    /**
     * 校验 {@code required} 对应的前置条件，不满足时抛出明确异常。
     */
    private static String required(String value, String field, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return result;
    }
    /**
     * 校验 {@code requireTenantAdmin} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }
    /**
     * 校验 {@code requireScope} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }
    public record CreateWebhookCommand(String name, String url) {
    }

    public record CreatedWebhook(
            UUID id,
            String name,
            String url,
            String status,
            String signingSecret,
            Instant createdAt
    ) {
    }
    public record WebhookHttpResult(int statusCode, String body, String retryAfter) {
    }
}

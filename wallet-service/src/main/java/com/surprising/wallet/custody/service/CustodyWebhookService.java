package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository.WebhookEndpointRecord;
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
import com.surprising.wallet.custody.repository.CustodyRepository;

@Service
public class CustodyWebhookService {
    private static final java.util.Set<String> DELIVERY_STATUSES = java.util.Set.of(
            "PENDING", "DELIVERING", "DELIVERED", "RETRY", "FAILED");
    private final CustodyRepository repository;    private final CustodyCryptoService crypto;    private final ObjectMapper objectMapper;    private final HttpClient httpClient;    private final boolean production;

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
    public List<Map<String, Object>> list(CustodyPrincipal principal) {
        requireScope(principal, "webhooks:read");
        return repository.listWebhookEndpoints(principal.tenantId());
    }

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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("webhook verification response must be valid JSON", e);
        }
        repository.markWebhookVerified(principal.tenantId(), endpointId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.VERIFY", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp, "{}");
        return repository.requireWebhookEndpoint(principal.tenantId(), endpointId);
    }

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

    public List<Map<String, Object>> deliveries(CustodyPrincipal principal, UUID endpointId,
                                                String status, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        String normalizedStatus = normalizeDeliveryStatus(status);
        return repository.listWebhookDeliveries(
                principal.tenantId(), endpointId, normalizedStatus, limit, offset);
    }

    public List<Map<String, Object>> deliveryAttempts(
            CustodyPrincipal principal, UUID deliveryId, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        if (deliveryId == null) {
            throw new IllegalArgumentException("deliveryId is required");
        }
        return repository.listWebhookDeliveryAttempts(
                principal.tenantId(), deliveryId, limit, offset);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void retry(CustodyPrincipal principal, UUID deliveryId, String sourceIp) {
        requireTenantAdmin(principal);
        repository.retryWebhookDelivery(principal.tenantId(), deliveryId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.DELIVERY_RETRY", "WEBHOOK_DELIVERY", deliveryId.toString(), sourceIp, "{}");
    }

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
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize webhook payload", e);
        }
    }
    private static String required(String value, String field, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return result;
    }
    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }
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

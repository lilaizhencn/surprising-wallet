package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.CustodyWebhookHttpClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.*;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyEventRepository;
import com.surprising.wallet.repository.CustodyWebhookDeliveryAttemptRepository;
import com.surprising.wallet.repository.CustodyWebhookDeliveryRepository;
import com.surprising.wallet.repository.CustodyWebhookEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Webhook 端点、领取、尝试回执与重试；HTTP 不参与数据库事务。 */
@Service
public class CustodyWebhookService {
    private static final Set<String> DELIVERY_STATUSES = Set.of("PENDING", "DELIVERING", "DELIVERED", "RETRY", "FAILED");
    private final CustodyWebhookEndpointRepository endpoints;
    private final CustodyWebhookDeliveryRepository deliveries;
    private final CustodyWebhookDeliveryAttemptRepository attempts;
    private final CustodyEventRepository events;
    private final CustodyAuditRepository audits;
    private final CustodyCryptoService crypto;
    private final ObjectMapper mapper;
    private final CustodyWebhookHttpClient http;
    private final TransactionTemplate transactions;

    public CustodyWebhookService(CustodyWebhookEndpointRepository endpoints,
                                 CustodyWebhookDeliveryRepository deliveries,
                                 CustodyWebhookDeliveryAttemptRepository attempts,
                                 CustodyEventRepository events, CustodyAuditRepository audits,
                                 CustodyCryptoService crypto, ObjectMapper mapper,
                                 CustodyWebhookHttpClient http, PlatformTransactionManager manager) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.events = events;
        this.audits = audits;
        this.crypto = crypto;
        this.mapper = mapper;
        this.http = http;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(rollbackFor = Throwable.class)
    public CreatedWebhook create(CustodyPrincipal principal, CreateWebhookCommand command, String sourceIp) {
        requireTenantAdmin(principal);
        String name = required(command.name(), "webhook name", 120);
        URI uri = http.validateEndpoint(command.url());
        String secret = "whsec_" + crypto.randomSecret(32);
        UUID id = UUID.randomUUID();
        WebhookEndpointView saved = endpoints.insert(id, principal.tenantId(), name, uri.toString(),
                crypto.encrypt(secret), principal.actorId());
        audits.insert(UUID.randomUUID(), principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.CREATE", "WEBHOOK_ENDPOINT", id.toString(), sourceIp, json(Map.of("url", uri.toString())));
        return new CreatedWebhook(saved.id(), saved.name(), saved.url(), saved.status(), secret, saved.createdAt());
    }

    public List<WebhookEndpointView> list(CustodyPrincipal principal) {
        requireScope(principal, "webhooks:read");
        return endpoints.list(principal.tenantId());
    }

    /** 读取和网络验证结束后，才开启状态变更与审计的短事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WebhookEndpointView verify(CustodyPrincipal principal, UUID endpointId, String sourceIp) {
        requireTenantAdmin(principal);
        WebhookEndpointRecord endpoint = requireEndpoint(principal.tenantId(), endpointId);
        if (!"PENDING_VERIFICATION".equals(endpoint.status())) {
            throw new IllegalStateException("only pending webhook endpoints can be verified");
        }
        String challenge = crypto.randomSecret(24);
        UUID eventId = UUID.randomUUID();
        String body = json(Map.of("id", eventId, "type", "WEBHOOK.VERIFICATION", "createdAt", Instant.now(),
                "data", Map.of("challenge", challenge)));
        var response = http.send(endpoint.url(), crypto.decrypt(endpoint.secretCiphertext()), eventId, "WEBHOOK.VERIFICATION", body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("webhook verification returned HTTP " + response.statusCode());
        }
        try {
            JsonNode echoed = mapper.readTree(response.body());
            if (!challenge.equals(echoed.path("challenge").asText())) {
                throw new IllegalStateException("webhook verification response did not echo the challenge");
            }
        } catch (JacksonException error) {
            throw new IllegalStateException("webhook verification response must be valid JSON", error);
        }
        return transactions.execute(status -> {
            if (endpoints.markVerified(principal.tenantId(), endpointId) != 1) {
                throw new IllegalStateException("webhook endpoint changed during verification");
            }
            audits.insert(UUID.randomUUID(), principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                    "WEBHOOK.VERIFY", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp, "{}");
            return endpoints.findView(principal.tenantId(), endpointId)
                    .orElseThrow(() -> new IllegalStateException("webhook endpoint not found"));
        });
    }

    @Transactional(rollbackFor = Throwable.class)
    public void setEnabled(CustodyPrincipal principal, UUID endpointId, boolean enabled, String sourceIp) {
        requireTenantAdmin(principal);
        var endpoint = requireEndpoint(principal.tenantId(), endpointId);
        if (enabled && endpoint.verifiedAt() == null) throw new IllegalStateException("verify the webhook endpoint before enabling it");
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (endpoints.updateStatus(principal.tenantId(), endpointId, status) != 1) throw new IllegalArgumentException("webhook endpoint not found");
        audits.insert(UUID.randomUUID(), principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.STATUS_CHANGE", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp, json(Map.of("status", status)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WebhookDeliveryView> deliveries(CustodyPrincipal principal, UUID endpointId, String status, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        return deliveriesForTenant(principal.tenantId(), endpointId, status, limit, offset);
    }

    /** 平台租户详情复用同租户分页与事件类型补充。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WebhookDeliveryView> deliveriesForTenant(UUID tenantId, UUID endpointId, String status, int limit, int offset) {
        List<WebhookDeliveryRecord> rows = deliveries.list(tenantId, endpointId, normalizeDeliveryStatus(status), limit, offset);
        if (rows.isEmpty()) return List.of();
        var payloads = events.findContents(tenantId, rows.stream().map(WebhookDeliveryRecord::eventId).collect(Collectors.toSet()));
        return rows.stream().map(row -> {
            var event = payloads.get(row.eventId());
            if (event == null) throw new IllegalStateException("webhook event not found");
            return new WebhookDeliveryView(row.id(), row.endpointId(), row.eventId(), event.eventType(),
                    event.aggregateType(), event.aggregateId(), row.status(),
                    row.attemptCount(), row.totalAttemptCount(), row.manualRetryCount(), row.nextAttemptAt(),
                    row.nextAttemptTrigger(), row.lastHttpStatus(), row.lastError(), row.createdAt(), row.deliveredAt());
        }).toList();
    }

    public List<WebhookDeliveryAttemptView> deliveryAttempts(CustodyPrincipal principal, UUID deliveryId, int limit, int offset) {
        requireScope(principal, "webhooks:read");
        if (deliveryId == null) throw new IllegalArgumentException("deliveryId is required");
        return attempts.list(principal.tenantId(), deliveryId, limit, offset);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void retry(CustodyPrincipal principal, UUID deliveryId, String sourceIp) {
        requireTenantAdmin(principal);
        if (deliveries.retry(principal.tenantId(), deliveryId) != 1) throw new IllegalStateException("webhook delivery not retryable");
        audits.insert(UUID.randomUUID(), principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.DELIVERY_RETRY", "WEBHOOK_DELIVERY", deliveryId.toString(), sourceIp, "{}");
    }

    @Transactional(rollbackFor = Throwable.class)
    public int retryFailed(CustodyPrincipal principal, UUID endpointId, String sourceIp) {
        requireTenantAdmin(principal);
        if (endpointId == null) throw new IllegalArgumentException("endpointId is required");
        requireEndpoint(principal.tenantId(), endpointId);
        int queued = deliveries.retryFailed(principal.tenantId(), endpointId);
        audits.insert(UUID.randomUUID(), principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "WEBHOOK.DELIVERY_RETRY_BATCH", "WEBHOOK_ENDPOINT", endpointId.toString(), sourceIp,
                json(Map.of("queued", queued, "statuses", List.of("FAILED", "RETRY"))));
        return queued;
    }

    @Transactional(rollbackFor = Throwable.class)
    public List<WebhookDeliveryTask> claimWebhookDeliveries(String workerId, int limit) {
        var claims = deliveries.claim(workerId, limit);
        Map<UUID, List<WebhookDeliveryClaim>> tenants = claims.stream()
                .collect(Collectors.groupingBy(WebhookDeliveryClaim::tenantId, LinkedHashMap::new, Collectors.toList()));
        List<WebhookDeliveryTask> tasks = new ArrayList<>();
        for (var tenant : tenants.entrySet()) {
            var endpointRows = endpoints.findAll(tenant.getKey(), tenant.getValue().stream().map(WebhookDeliveryClaim::endpointId).collect(Collectors.toSet()));
            var eventRows = events.findContents(tenant.getKey(), tenant.getValue().stream().map(WebhookDeliveryClaim::eventId).collect(Collectors.toSet()));
            for (var claim : tenant.getValue()) {
                var endpoint = endpointRows.get(claim.endpointId());
                var event = eventRows.get(claim.eventId());
                if (endpoint == null || event == null) throw new IllegalStateException("webhook endpoint or event not found");
                if ("RECOVERY".equals(claim.attemptTrigger())) attempts.recoverStale(claim.tenantId(), claim.id());
                UUID attemptId = UUID.randomUUID();
                if (attempts.insert(attemptId, claim.tenantId(), claim.id(), claim.totalAttemptCount(),
                        claim.manualRetryCount(), claim.attemptTrigger(), workerId) != 1) {
                    throw new IllegalStateException("failed to record webhook attempt");
                }
                tasks.add(new WebhookDeliveryTask(claim.id(), claim.tenantId(), claim.endpointId(), claim.eventId(),
                        claim.attemptCount(), claim.totalAttemptCount(), claim.manualRetryCount(), claim.attemptTrigger(),
                        attemptId, workerId, endpoint.url(), endpoint.secretCiphertext(), event.eventType(), event.payload()));
            }
        }
        return tasks;
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean markWebhookDelivered(WebhookDeliveryTask task, int httpStatus, String response, long durationMs) {
        if (deliveries.delivered(task.tenantId(), task.id(), task.endpointId(), task.eventId(), task.workerId(),
                task.totalAttemptCount(), httpStatus, response) != 1) return false;
        if (attempts.delivered(task.tenantId(), task.id(), task.attemptId(), task.workerId(), task.totalAttemptCount(),
                httpStatus, response, durationMs) != 1) throw new IllegalStateException("webhook attempt ownership changed");
        if (endpoints.touchDelivery(task.tenantId(), task.endpointId()) != 1) throw new IllegalStateException("webhook endpoint not found");
        return true;
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean markWebhookFailed(WebhookDeliveryTask task, Integer httpStatus, String error,
                                     String response, Instant nextAttempt, boolean terminal, long durationMs) {
        if (!terminal && nextAttempt == null) throw new IllegalArgumentException("retry time is required");
        Instant scheduled = terminal ? null : nextAttempt;
        if (deliveries.failed(task.tenantId(), task.id(), task.endpointId(), task.eventId(), task.workerId(),
                task.totalAttemptCount(), httpStatus, error, response, terminal, scheduled) != 1) return false;
        if (attempts.failed(task.tenantId(), task.id(), task.attemptId(), task.workerId(), task.totalAttemptCount(),
                httpStatus, error, response, terminal, scheduled, durationMs) != 1) {
            throw new IllegalStateException("webhook attempt ownership changed");
        }
        return true;
    }

    private WebhookEndpointRecord requireEndpoint(UUID tenantId, UUID id) {
        return endpoints.find(tenantId, id).orElseThrow(() -> new IllegalArgumentException("webhook endpoint not found"));
    }

    private static String normalizeDeliveryStatus(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (!DELIVERY_STATUSES.contains(value)) throw new IllegalArgumentException("unsupported webhook delivery status");
        return value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException error) { throw new IllegalStateException("failed to serialize webhook payload", error); }
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and must not exceed " + max + " characters");
        return normalized;
    }

    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }

    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) throw new CustodyForbiddenException(scope + " scope required");
    }

    public record CreateWebhookCommand(String name, String url) { }
    public record CreatedWebhook(UUID id, String name, String url, String status, String signingSecret, Instant createdAt) { }
    public record WebhookDeliveryView(UUID id, UUID endpointId, UUID eventId, String eventType,
                                      String aggregateType, String aggregateId, String status,
                                      int attemptCount, int totalAttemptCount, int manualRetryCount, Instant nextAttemptAt,
                                      String nextAttemptTrigger, Integer lastHttpStatus, String lastError, Instant createdAt, Instant deliveredAt) { }
}

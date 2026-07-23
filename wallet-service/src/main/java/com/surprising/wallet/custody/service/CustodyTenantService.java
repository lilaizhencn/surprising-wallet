package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.custody.repository.CustodyRepository.GasAccountRecord;
import com.surprising.wallet.custody.repository.CustodyRepository.TenantRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

@Service
public class CustodyTenantService {
    private final CustodyRepository repository;
    private final CustodyPasswordService passwords;
    private final ObjectMapper objectMapper;

    public CustodyTenantService(CustodyRepository repository, CustodyPasswordService passwords,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.passwords = passwords;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord create(CustodyPrincipal actor, CreateTenantCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        String slug = normalizeSlug(command.slug());
        String name = required(command.name(), "tenant name", 160);
        String email = normalizeEmail(command.adminEmail());
        String displayName = required(command.adminDisplayName(), "tenant admin display name", 120);
        UUID tenantId = UUID.randomUUID();
        TenantRecord result = repository.createTenant(
                tenantId, slug, name, UUID.randomUUID(), email, displayName,
                passwords.hash(command.adminPassword()));
        repository.audit(tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.CREATE",
                "TENANT", tenantId.toString(), sourceIp,
                json(Map.of("slug", slug, "name", name, "adminEmail", email)));
        return result;
    }

    public TenantPage list(CustodyPrincipal actor, String search, String status,
                           int limit, int offset) {
        requirePlatformAdmin(actor);
        String normalizedSearch = optional(search, 160);
        String normalizedStatus = optionalTenantStatus(status);
        int normalizedLimit = Math.min(Math.max(limit, 1), 200);
        int normalizedOffset = Math.max(offset, 0);
        return new TenantPage(
                repository.listTenants(
                        normalizedSearch, normalizedStatus, normalizedLimit, normalizedOffset),
                repository.countTenants(normalizedSearch, normalizedStatus),
                normalizedLimit,
                normalizedOffset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantDetail detail(CustodyPrincipal actor, UUID tenantId) {
        requirePlatformAdmin(actor);
        TenantRecord tenant = repository.requireTenant(tenantId);
        return new TenantDetail(
                tenant,
                repository.tenantOperationsSummary(tenantId),
                repository.onboardingStatus(tenantId),
                repository.listTenantUsers(tenantId),
                repository.tenantAssetOverview(tenantId),
                repository.listAddresses(tenantId, "", "", "", "", 20, 0),
                repository.listGasAccounts(tenantId),
                repository.listApiKeys(tenantId),
                repository.listIpRules(tenantId),
                repository.listWebhookEndpoints(tenantId),
                repository.listWebhookDeliveries(tenantId, null, null, 20, 0),
                repository.listCustodyDeposits(tenantId, "", "", "", "", 20, 0),
                repository.listCustodyWithdrawals(tenantId, "", "", "", "", 20, 0),
                repository.listAudit(tenantId, 50, 0));
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord update(CustodyPrincipal actor, UUID tenantId,
                               UpdateTenantCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TenantRecord current = repository.requireTenant(tenantId);
        String name = command.name() == null
                ? current.name()
                : required(command.name(), "tenant name", 160);
        String displayCurrency = command.displayCurrency() == null
                ? current.displayCurrency()
                : normalizeDisplayCurrency(command.displayCurrency());
        repository.updateTenantProfile(tenantId, name, displayCurrency);
        repository.audit(tenantId, "PLATFORM_USER", actor.actorId().toString(),
                "TENANT.UPDATE", "TENANT", tenantId.toString(), sourceIp,
                json(Map.of(
                        "name", name,
                        "displayCurrency", displayCurrency)));
        return repository.requireTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord updateStatus(CustodyPrincipal actor, UUID tenantId, String status, String sourceIp) {
        requirePlatformAdmin(actor);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!SetHolder.TENANT_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("tenant status must be ACTIVE or SUSPENDED");
        }
        repository.updateTenantStatus(tenantId, normalized);
        int revokedSessions = "SUSPENDED".equals(normalized)
                ? repository.revokeTenantSessions(tenantId)
                : 0;
        repository.audit(tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.STATUS_CHANGE",
                "TENANT", tenantId.toString(), sourceIp, json(Map.of(
                        "status", normalized,
                        "revokedSessions", revokedSessions)));
        return repository.requireTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> unlockAdministrator(
            CustodyPrincipal actor, UUID tenantId, UUID userId, String sourceIp) {
        requirePlatformAdmin(actor);
        repository.requireTenant(tenantId);
        Map<String, Object> administrator =
                repository.unlockTenantAdministrator(tenantId, userId);
        repository.audit(tenantId, "PLATFORM_USER", actor.actorId().toString(),
                "TENANT_ADMIN.UNLOCK", "TENANT_USER", userId.toString(), sourceIp,
                json(Map.of("email", administrator.get("email"))));
        return administrator;
    }

    private void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize audit detail", e);
        }
    }

    private static String normalizeSlug(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!slug.matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")) {
            throw new IllegalArgumentException(
                    "slug must contain 3-64 lowercase letters, digits or internal hyphens");
        }
        return slug;
    }

    private static String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("valid tenant admin email is required");
        }
        return email;
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "search must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String optionalTenantStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !SetHolder.TENANT_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "tenant status must be ACTIVE or SUSPENDED");
        }
        return normalized;
    }

    private static String normalizeDisplayCurrency(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{3,12}$")) {
            throw new IllegalArgumentException(
                    "display currency must contain 3-12 uppercase letters or digits");
        }
        return normalized;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> TENANT_STATUSES = java.util.Set.of("ACTIVE", "SUSPENDED");
    }

    public record CreateTenantCommand(
            String slug,
            String name,
            String adminEmail,
            String adminDisplayName,
            String adminPassword
    ) {
    }

    public record UpdateTenantCommand(
            String name,
            String displayCurrency
    ) {
    }

    public record TenantPage(
            List<Map<String, Object>> items,
            long total,
            int limit,
            int offset
    ) {
    }

    public record TenantDetail(
            TenantRecord tenant,
            Map<String, Object> statistics,
            Map<String, Object> onboarding,
            List<Map<String, Object>> administrators,
            List<Map<String, Object>> assets,
            List<AddressRecord> recentAddresses,
            List<GasAccountRecord> gasAccounts,
            List<Map<String, Object>> apiKeys,
            List<Map<String, Object>> ipRules,
            List<Map<String, Object>> webhooks,
            List<Map<String, Object>> webhookDeliveries,
            List<Map<String, Object>> recentDeposits,
            List<Map<String, Object>> recentWithdrawals,
            List<Map<String, Object>> recentAudit
    ) {
    }
}

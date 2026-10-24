package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.ApiKeyView;
import com.surprising.wallet.model.custody.GasAccountRecord;
import com.surprising.wallet.model.custody.IpRuleView;
import com.surprising.wallet.model.custody.TenantRecord;
import com.surprising.wallet.model.custody.TenantUserView;
import com.surprising.wallet.model.custody.WebhookEndpointView;
import com.surprising.wallet.repository.CustodyWebhookEndpointRepository;
import com.surprising.wallet.repository.CustodyApiKeyRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyIpRuleRepository;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 平台租户创建、资料与状态管理、管理员解锁和运营详情。 */
@Service
@RequiredArgsConstructor
public class CustodyTenantService {
    private static final Set<String> TENANT_STATUSES = Set.of("ACTIVE", "SUSPENDED");
    private final CustodyRepository repository;
    private final CustodyPasswordService passwords;
    private final ObjectMapper objectMapper;
    private final CustodyTransactionQueryService history;
    private final CustodyAddressService addresses;
    private final CustodyTenantUserRepository users;
    private final CustodySessionRepository sessions;
    private final CustodyApiKeyRepository apiKeys;
    private final CustodyIpRuleRepository ipRules;
    private final CustodyAuditRepository audits;
    private final CustodyWebhookEndpointRepository webhookEndpoints;
    private final CustodyWebhookService webhooks;

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
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.CREATE",
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
                users.listByTenant(tenantId),
                repository.tenantAssetOverview(tenantId),
                addresses.listForTenant(tenantId, "", "", "", "", 20, 0),
                repository.listGasAccounts(tenantId),
                apiKeys.listByTenant(tenantId),
                ipRules.list(tenantId),
                webhookEndpoints.list(tenantId),
                webhooks.deliveriesForTenant(tenantId, null, null, 20, 0),
                history.deposits(tenantId, "", "", "", "", 20, 0),
                history.withdrawals(tenantId, "", "", "", "", 20, 0),
                audits.list(tenantId, 50, 0));
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
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(),
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
        if (!TENANT_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("tenant status must be ACTIVE or SUSPENDED");
        }

        repository.updateTenantStatus(tenantId, normalized);
        int revokedSessions = "SUSPENDED".equals(normalized)
                ? sessions.revokeByTenant(tenantId)
                : 0;
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.STATUS_CHANGE",
                "TENANT", tenantId.toString(), sourceIp, json(Map.of(
                        "status", normalized,
                        "revokedSessions", revokedSessions)));
        return repository.requireTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantUserView unlockAdministrator(
            CustodyPrincipal actor, UUID tenantId, UUID userId, String sourceIp) {
        requirePlatformAdmin(actor);
        repository.requireTenant(tenantId);
        if (users.unlockAdministrator(tenantId, userId) != 1) {
            throw new IllegalArgumentException("active tenant administrator not found");
        }

        TenantUserView administrator = users.findView(tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("tenant administrator not found"));
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(),
                "TENANT_ADMIN.UNLOCK", "TENANT_USER", userId.toString(), sourceIp,
                json(Map.of("email", administrator.email())));
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
        } catch (JacksonException e) {
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
        if (!normalized.isEmpty() && !TENANT_STATUSES.contains(normalized)) {
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
            List<TenantUserView> administrators,
            List<Map<String, Object>> assets,
            List<CustodyAddressRecord> recentAddresses,
            List<GasAccountRecord> gasAccounts,
            List<ApiKeyView> apiKeys,
            List<IpRuleView> ipRules,
            List<WebhookEndpointView> webhooks,
            List<CustodyWebhookService.WebhookDeliveryView> webhookDeliveries,
            List<CustodyTransactionQueryService.DepositView> recentDeposits,
            List<CustodyTransactionQueryService.WithdrawalView> recentWithdrawals,
            List<Map<String, Object>> recentAudit
    ) {
    }
}

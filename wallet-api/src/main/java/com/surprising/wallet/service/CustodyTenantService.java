package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.TenantRecord;
import com.surprising.wallet.model.custody.TenantUserView;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant creation and administrator/status changes commit with their audit facts.
 */
@Service
@RequiredArgsConstructor
public class CustodyTenantService {

    private final CustodyTenantTableRepository tenants;

    private final CustodyPasswordService passwords;

    private final ObjectMapper mapper;

    private final CustodyTenantUserRepository users;

    private final CustodySessionRepository sessions;

    private final CustodyAuditRepository audits;

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord create(CustodyPrincipal actor, CreateTenantCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        String slug = normalizeSlug(command.slug());
        String name = required(command.name(), "tenant name", 160);
        String email = normalizeEmail(command.adminEmail());
        String displayName = required(command.adminDisplayName(), "tenant admin display name", 120);
        String passwordHash = passwords.hash(command.adminPassword());
        UUID tenant = UUID.randomUUID();
        tenants.insert(tenant, slug, name);
        users.insertTenantAdmin(UUID.randomUUID(), tenant, email, displayName, passwordHash);
        audits.insert(UUID.randomUUID(), tenant, "PLATFORM_USER", actor.actorId().toString(), "TENANT.CREATE", "TENANT", tenant.toString(), sourceIp, json(Map.of("slug", slug, "name", name, "adminEmail", email)));
        return requireTenant(tenant);
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord update(CustodyPrincipal actor, UUID tenantId, UpdateTenantCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TenantRecord current = requireTenant(tenantId);
        String name = command.name() == null ? current.name() : required(command.name(), "tenant name", 160);
        String displayCurrency = command.displayCurrency() == null ? current.displayCurrency() : normalizeDisplayCurrency(command.displayCurrency());
        if (tenants.updateProfile(tenantId, name, displayCurrency) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.UPDATE", "TENANT", tenantId.toString(), sourceIp, json(Map.of("name", name, "displayCurrency", displayCurrency)));
        return requireTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord updateStatus(CustodyPrincipal actor, UUID tenantId, String status, String sourceIp) {
        requirePlatformAdmin(actor);
        String normalized = requireStatus(status);
        if (tenants.updateStatus(tenantId, normalized) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
        int revokedSessions = "SUSPENDED".equals(normalized) ? sessions.revokeByTenant(tenantId) : 0;
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.STATUS_CHANGE", "TENANT", tenantId.toString(), sourceIp, json(Map.of("status", normalized, "revokedSessions", revokedSessions)));
        return requireTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantUserView unlockAdministrator(CustodyPrincipal actor, UUID tenantId, UUID userId, String sourceIp) {
        requirePlatformAdmin(actor);
        requireTenant(tenantId);
        if (users.unlockAdministrator(tenantId, userId) != 1) {
            throw new IllegalArgumentException("active tenant administrator not found");
        }
        TenantUserView administrator = users.findView(tenantId, userId).orElseThrow(() -> new IllegalArgumentException("tenant administrator not found"));
        audits.insert(UUID.randomUUID(), tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT_ADMIN.UNLOCK", "TENANT_USER", userId.toString(), sourceIp, json(Map.of("email", administrator.email())));
        return administrator;
    }

    private TenantRecord requireTenant(UUID tenant) {
        return tenants.findById(tenant).orElseThrow(() -> new IllegalArgumentException("tenant not found"));
    }

    static String requireStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new IllegalArgumentException("tenant status must be ACTIVE or SUSPENDED");
        }
        return status;
    }

    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize audit detail", error);
        }
    }

    private static String normalizeSlug(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!slug.matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")) {
            throw new IllegalArgumentException("slug must contain 3-64 lowercase letters, digits or internal hyphens");
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

    private static String normalizeDisplayCurrency(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{3,12}$")) {
            throw new IllegalArgumentException("display currency must contain 3-12 uppercase letters or digits");
        }
        return normalized;
    }

    public record CreateTenantCommand(
            String slug,
            String name,
            String adminEmail,
            String adminDisplayName,
            String adminPassword) {
    }

    public record UpdateTenantCommand(String name, String displayCurrency) {
    }
}

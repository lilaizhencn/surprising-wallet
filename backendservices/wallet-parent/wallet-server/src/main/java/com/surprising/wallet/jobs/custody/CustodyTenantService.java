package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.jobs.custody.CustodyRepository.TenantRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    public List<Map<String, Object>> list(CustodyPrincipal actor, int limit, int offset) {
        requirePlatformAdmin(actor);
        return repository.listTenants(limit, offset);
    }

    public TenantRecord detail(CustodyPrincipal actor, UUID tenantId) {
        requirePlatformAdmin(actor);
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
        repository.audit(tenantId, "PLATFORM_USER", actor.actorId().toString(), "TENANT.STATUS_CHANGE",
                "TENANT", tenantId.toString(), sourceIp, json(Map.of("status", normalized)));
        return repository.requireTenant(tenantId);
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
}

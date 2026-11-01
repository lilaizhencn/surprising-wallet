package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.TenantUserRecord;
import com.surprising.wallet.model.custody.TenantUserView;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Tenant-owned console members. Approver role alone does not authorize an MPC signature. */
@Service
@RequiredArgsConstructor
public class CustodyTenantUserService {
    private static final Set<String> MEMBER_ROLES = Set.of("OPERATOR", "APPROVER", "VIEWER");
    private final CustodyTenantUserRepository users;
    private final CustodySessionRepository sessions;
    private final CustodyPasswordService passwords;
    private final CustodyAuditRepository audits;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public List<TenantUserView> list(CustodyPrincipal actor) {
        requireAdministrator(actor);
        return users.listByTenant(actor.tenantId());
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantUserView create(CustodyPrincipal actor, CreateMember command, String ip) {
        requireAdministrator(actor);
        if (command == null) throw new IllegalArgumentException("member is required");
        String email = required(command.email(), "email", 254).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("valid email is required");
        String name = required(command.displayName(), "displayName", 120);
        String role = role(command.role());
        String hash = passwords.hash(command.password());
        UUID id = UUID.randomUUID();
        try { users.insertMember(id, actor.tenantId(), email, name, hash, role); }
        catch (org.springframework.dao.DuplicateKeyException conflict) { throw new IllegalArgumentException("email is unavailable"); }
        audit(actor, id, "TENANT_USER.CREATE", Map.of("email", email, "role", role), ip);
        return users.findView(actor.tenantId(), id).orElseThrow();
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantUserView update(CustodyPrincipal actor, UUID id, UpdateMember command, String ip) {
        requireAdministrator(actor);
        var previous=requireMember(actor, id);
        if (command == null) throw new IllegalArgumentException("member is required");
        String name = required(command.displayName(), "displayName", 120);
        String role = role(command.role());
        String status = required(command.status(), "status", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw new IllegalArgumentException("invalid member status");
        if (users.updateMember(actor.tenantId(), id, name, role, status) != 1) throw new IllegalStateException("member changed");
        boolean approvalRevocation=(!previous.status().equals("DISABLED") && status.equals("DISABLED")) || (previous.role().equals("APPROVER") && !role.equals("APPROVER"));
        if(approvalRevocation)
            users.enqueueApprovalRevocation(actor.tenantId(),id,actor.actorId());
        int revoked = sessions.revokeByUser(actor.tenantId(), id);
        audit(actor, id, "TENANT_USER.UPDATE", Map.of("role", role, "status", status, "revokedSessions", revoked,"approvalRevocationQueued",approvalRevocation), ip);
        return users.findView(actor.tenantId(), id).orElseThrow();
    }

    @Transactional(rollbackFor = Throwable.class)
    public void resetPassword(CustodyPrincipal actor, UUID id, String password, String ip) {
        requireAdministrator(actor);
        requireMember(actor, id);
        if (users.resetMemberPassword(actor.tenantId(), id, passwords.hash(password)) != 1) throw new IllegalStateException("member changed");
        int revoked = sessions.revokeByUser(actor.tenantId(), id);
        audit(actor, id, "TENANT_USER.RESET_PASSWORD", Map.of("revokedSessions", revoked), ip);
    }

    private void requireAdministrator(CustodyPrincipal actor) {
        if (actor == null || actor.actorType() != CustodyPrincipal.ActorType.TENANT_USER
                || actor.tenantId() == null || !"TENANT_ADMIN".equals(actor.role()))
            throw new CustodyForbiddenException("tenant administrator required");
        var current = users.findById(actor.actorId()).orElseThrow(() -> new CustodyForbiddenException("tenant administrator required"));
        if (!actor.tenantId().equals(current.tenantId()) || !"TENANT_ADMIN".equals(current.role()) || !"ACTIVE".equals(current.status()))
            throw new CustodyForbiddenException("active tenant administrator required");
    }

    private TenantUserRecord requireMember(CustodyPrincipal actor, UUID id) {
        var member = users.lockMember(actor.tenantId(), id).orElseThrow(() -> new IllegalArgumentException("member not found"));
        if (!MEMBER_ROLES.contains(member.role()) || actor.actorId().equals(id))
            throw new CustodyForbiddenException("only tenant subaccounts can be managed here");
        return member;
    }

    private void audit(CustodyPrincipal actor, UUID id, String action, Map<String, ?> detail, String ip) {
        audits.insert(UUID.randomUUID(), actor.tenantId(), actor.actorType().name(), actor.actorId().toString(),
                action, "TENANT_USER", id.toString(), ip, json.writeValueAsString(detail));
    }

    private static String role(String value) {
        String role = required(value, "role", 32).toUpperCase(Locale.ROOT);
        if (!MEMBER_ROLES.contains(role)) throw new IllegalArgumentException("role must be OPERATOR, APPROVER or VIEWER");
        return role;
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " is invalid");
        return value.trim();
    }

    public record CreateMember(String email, String displayName, String password, String role) { }
    public record UpdateMember(String displayName, String role, String status) { }
}

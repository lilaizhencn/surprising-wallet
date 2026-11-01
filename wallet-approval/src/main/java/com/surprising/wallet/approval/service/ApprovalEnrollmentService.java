package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.security.PasswordHashes;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Offline trusted enrollment only; deliberately has no HTTP or Wallet-admin enrollment endpoint. */
public class ApprovalEnrollmentService {
    private final ApprovalTenantRepository tenants;
    private final ApprovalMemberRepository members;
    private final ApprovalAuditRepository audit;
    public ApprovalEnrollmentService(ApprovalTenantRepository tenants,ApprovalMemberRepository members,ApprovalAuditRepository audit) {
        this.tenants=tenants;this.members=members;this.audit=audit;
    }
    @Transactional(rollbackFor=Throwable.class)
    public void enroll(Enrollment command) {
        if(command.tenantId()==null || command.memberId()==null || command.tenantSlug()==null || !command.tenantSlug().matches("[a-z0-9][a-z0-9_-]{0,95}"))
            throw new IllegalArgumentException("independent tenant and member identities required");
        String email=command.email()==null?"":command.email().trim().toLowerCase(Locale.ROOT);
        if(email.length()>254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || command.displayName()==null
                || command.displayName().isBlank() || command.displayName().length()>120
                || command.displayName().chars().anyMatch(Character::isISOControl) || !Set.of("TENANT_ADMIN","APPROVER").contains(command.role()))
            throw new IllegalArgumentException("invalid independent member enrollment");
        String hash=new PasswordHashes().hash(command.password());
        tenants.enroll(command.tenantId(),command.tenantSlug());
        members.insert(command.tenantId(),command.memberId(),email,command.displayName().trim(),hash,command.role());
        audit.insert(command.tenantId(),"SYSTEM","offline-enrollment","MEMBER.ENROLL",command.memberId(),Map.of("email",email,"role",command.role()));
    }
    public record Enrollment(UUID tenantId,String tenantSlug,UUID memberId,String email,String displayName,String role,String password) { }
}

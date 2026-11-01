package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.security.PasswordHashes;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent administrators may enroll reviewers, never service clients or Wallet administrators. */
@Service
public class ApprovalMemberService {
    private final ApprovalTenantRepository tenants;
    private final ApprovalMemberRepository members;
    private final ApprovalSessionRepository sessions;
    private final ApprovalAuditRepository audit;
    private final Clock clock;
    private final ApprovalMemberRevocationRepository revocations;
    private final PasswordHashes passwords=new PasswordHashes();
    private final SecureRandom random=new SecureRandom();
    public ApprovalMemberService(ApprovalTenantRepository tenants,ApprovalMemberRepository members,ApprovalSessionRepository sessions,
            ApprovalAuditRepository audit,Clock clock,ApprovalMemberRevocationRepository revocations) {
        this.tenants=tenants;this.members=members;this.sessions=sessions;this.audit=audit;this.clock=clock;this.revocations=revocations;
    }
    @Transactional(readOnly=true)
    public MemberPage list(UUID tenant,UUID actor,int limit,int offset) {
        administrator(tenant,actor);int size=size(limit),start=Math.max(0,offset);
        var page=members.list(tenant,size,start);var fences=revocations.sequences(tenant,page.stream().map(ApprovalMemberRepository.ManagedMember::id).toList());
        return new MemberPage(page.stream().map(m->new MemberView(m.id(),m.email(),m.displayName(),m.role(),m.status(),m.createdAt(),m.updatedAt(),fences.getOrDefault(m.id(),0L))).toList(),members.count(tenant),size,start);
    }
    @Transactional(rollbackFor=Throwable.class)
    public Activation invite(UUID tenant,UUID actor,Invite input) {
        lockAdministrator(tenant,actor);
        require(input!=null && input.memberId()!=null && !input.memberId().equals(actor),"a distinct Wallet subaccount ID is required");
        String email=input.email()==null?"":input.email().trim().toLowerCase(Locale.ROOT);
        require(email.length()<=254 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") && input.displayName()!=null
                && !input.displayName().isBlank() && input.displayName().length()<=120 && input.displayName().chars().noneMatch(Character::isISOControl),"invalid independent reviewer details");
        reason(input.reason());String token=secret();Instant expires=clock.instant().plusSeconds(86400);
        acknowledgeRevocation(tenant,input.memberId(),input.acknowledgedWalletRevocation());
        try { members.invite(tenant,input.memberId(),email,input.displayName().trim(),passwords.hash(secret()),hash(token),expires); }
        catch(DuplicateKeyException conflict) { throw new IllegalArgumentException("independent reviewer ID or email is unavailable"); }
        audit.insert(tenant,"MEMBER",actor.toString(),"MEMBER.INVITE",input.memberId(),Map.of("email",email,"reason",input.reason(),"expiresAt",expires,"acknowledgedWalletRevocation",input.acknowledgedWalletRevocation()));
        return new Activation(input.memberId(),token,expires);
    }
    @Transactional(rollbackFor=Throwable.class)
    public Activation reset(UUID tenant,UUID actor,UUID member,String reason,long acknowledgedWalletRevocation) {
        lockAdministrator(tenant,actor);reviewer(tenant,actor,member);reason(reason);
        acknowledgeRevocation(tenant,member,acknowledgedWalletRevocation);
        String token=secret();Instant expires=clock.instant().plusSeconds(86400);
        members.rotateActivation(tenant,member,hash(token),expires);sessions.revokeMember(tenant,member);
        audit.insert(tenant,"MEMBER",actor.toString(),"MEMBER.RESET",member,Map.of("reason",reason,"expiresAt",expires,"acknowledgedWalletRevocation",acknowledgedWalletRevocation));
        return new Activation(member,token,expires);
    }
    @Transactional(rollbackFor=Throwable.class)
    public void disable(UUID tenant,UUID actor,UUID member,String reason) {
        lockAdministrator(tenant,actor);var target=reviewer(tenant,actor,member);reason(reason);
        if(target.status().equals("DISABLED"))return;
        members.disable(tenant,member);sessions.revokeMember(tenant,member);
        audit.insert(tenant,"MEMBER",actor.toString(),"MEMBER.DISABLE",member,Map.of("reason",reason));
    }
    /** High-entropy activation capability, valid once, stored only as SHA-256 and never creates a login session. */
    @Transactional(rollbackFor=Throwable.class)
    public void activate(String token,String password) {
        require(token!=null && token.matches("[0-9a-f]{64}"),"activation link is invalid or expired");
        String tokenHash=hash(token);
        var identity=members.activationIdentity(tokenHash).orElseThrow(()->new IllegalArgumentException("activation link is invalid or expired"));
        String passwordHash=passwords.hash(password);
        require(tenants.lockActive(identity.tenantId()),"activation link is invalid or expired");
        members.lock(identity.tenantId(),identity.id()).orElseThrow(()->new IllegalArgumentException("activation link is invalid or expired"));
        require(members.activate(identity.tenantId(),identity.id(),tokenHash,passwordHash,clock.instant())==1,"activation link is invalid or expired");
        sessions.revokeMember(identity.tenantId(),identity.id());
        audit.insert(identity.tenantId(),"MEMBER",identity.id().toString(),"MEMBER.ACTIVATE",identity.id(),Map.of());
    }
    @Transactional(readOnly=true)
    public AuditPage audits(UUID tenant,UUID actor,String action,int limit,int offset) {
        administrator(tenant,actor);require(action!=null && (action.isEmpty() || action.matches("[A-Z_.]{1,64}")),"invalid audit action filter");
        int size=size(limit),start=Math.max(0,offset);return new AuditPage(audit.list(tenant,action,size,start),audit.count(tenant,action),size,start);
    }
    private void lockAdministrator(UUID tenant,UUID actor) {
        if(!tenants.lockActive(tenant))throw new SecurityException("independent approval tenant disabled");administrator(tenant,actor);
    }
    private void administrator(UUID tenant,UUID actor) {
        if(!tenants.active(tenant) || members.active(tenant,actor).filter(m->m.role().equals("TENANT_ADMIN")).isEmpty())
            throw new SecurityException("independent approval administrator required");
    }
    private ApprovalMemberRepository.ManagedMember reviewer(UUID tenant,UUID actor,UUID id) {
        require(id!=null && !id.equals(actor),"cannot change the current independent administrator");
        var target=members.lock(tenant,id).orElseThrow(()->new IllegalArgumentException("independent reviewer not found"));
        require(target.role().equals("APPROVER"),"independent administrators require trusted host administration");return target;
    }
    private String secret() { byte[] bytes=new byte[32];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes); }
    @Transactional(readOnly=true)
    public long revocation(UUID tenant,UUID actor,UUID member) {administrator(tenant,actor);return revocationSequence(tenant,member);}
    private long revocationSequence(UUID tenant,UUID member){return revocations.find(tenant,member).map(com.surprising.wallet.common.approval.ApprovalMemberRevocation::sequence).orElse(0L);}
    private void acknowledgeRevocation(UUID tenant,UUID member,long acknowledged) {
        require(acknowledged>=0 && acknowledged==revocationSequence(tenant,member),"independent administrator must explicitly acknowledge the current Wallet revocation before re-enrollment");
    }
    private static String hash(String token) { return SigningAuthorization.hash(token.getBytes(StandardCharsets.US_ASCII)); }
    private static int size(int requested) { return Math.min(Math.max(1,requested),100); }
    private static void reason(String value) { require(value!=null && value.trim().length()>=5 && value.length()<=512 && value.chars().noneMatch(Character::isISOControl),"an audit reason of 5 to 512 characters is required"); }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
    public record Invite(UUID memberId,String email,String displayName,String reason,long acknowledgedWalletRevocation) { }
    public record Activation(UUID memberId,String token,Instant expiresAt) { }
    public record MemberView(UUID id,String email,String displayName,String role,String status,Instant createdAt,Instant updatedAt,long walletRevocationSequence) { }
    public record MemberPage(List<MemberView> items,long total,int limit,int offset) { }
    public record AuditPage(List<ApprovalAuditRepository.Entry> items,long total,int limit,int offset) { }
}

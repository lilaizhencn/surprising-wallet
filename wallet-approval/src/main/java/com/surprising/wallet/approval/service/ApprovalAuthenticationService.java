package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.model.ApprovalPrincipal;
import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.security.PasswordHashes;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalAuthenticationService {
    private final ApprovalMemberRepository members;
    private final ApprovalSessionRepository sessions;
    private final ApprovalTenantRepository tenants;
    private final ApprovalAuditRepository audit;
    private final Clock clock;
    private final PasswordHashes passwords=new PasswordHashes();
    private final SecureRandom random=new SecureRandom();
    private final String dummy=passwords.hash("independent-dummy-password-"+java.util.UUID.randomUUID());
    public ApprovalAuthenticationService(ApprovalMemberRepository members,ApprovalSessionRepository sessions,
            ApprovalTenantRepository tenants,ApprovalAuditRepository audit,Clock clock) {
        this.members=members;this.sessions=sessions;this.tenants=tenants;this.audit=audit;this.clock=clock;
    }
    /** Failed authentication is a committed result so persistent throttling cannot roll back. */
    @Transactional(rollbackFor=Throwable.class)
    public Login login(String email,String password) {
        var member=authenticate(email,password);
        if(member==null)return null;
        Instant now=clock.instant();
        String token="as_"+secret(),csrf=secret();Instant expires=now.plusSeconds(8*3600);
        sessions.insert(member.tenantId(),member.id(),hash(token),csrf,expires);
        audit.insert(member.tenantId(),"MEMBER",member.id().toString(),"AUTH.LOGIN",member.id(),Map.of());
        return new Login(token,expires,new ApprovalPrincipal(member.tenantId(),member.id(),member.role(),member.email(),member.displayName(),csrf));
    }
    /** A decision checks the independent password every time and never creates an approval session. */
    @Transactional(rollbackFor=Throwable.class)
    public ApprovalPrincipal verifyForDecision(UUID tenant,UUID memberId,String password) {
        var member=authenticate(members.credentialByIdentity(tenant,memberId),password);
        if(member==null)return null;
        audit.insert(member.tenantId(),"MEMBER",member.id().toString(),"AUTH.DECISION_VERIFY",member.id(),Map.of());
        return new ApprovalPrincipal(member.tenantId(),member.id(),member.role(),member.email(),member.displayName(),null);
    }
    private ApprovalMemberRepository.Credential authenticate(String email,String password) {
        if(email==null || email.length()>254) { passwords.verify(password,dummy);return null; }
        return authenticate(members.credentialByEmail(email.trim().toLowerCase(Locale.ROOT)),password);
    }
    private ApprovalMemberRepository.Credential authenticate(Optional<ApprovalMemberRepository.Credential> found,String password) {
        if(found.isEmpty()) { passwords.verify(password,dummy);return null; }
        var member=found.get();Instant now=clock.instant();
        if(member.lockedUntil()!=null && now.isBefore(member.lockedUntil())) return null;
        if(!passwords.verify(password,member.passwordHash())) {
            int attempts=member.failedAttempts()+1;
            members.failed(member.tenantId(),member.id(),attempts>=5?0:attempts,attempts>=5?now.plusSeconds(900):null);
            audit.insert(member.tenantId(),"MEMBER",member.id().toString(),"AUTH.LOGIN_FAILED",member.id(),Map.of("attempts",attempts));
            return null;
        }
        if(!member.status().equals("ACTIVE") || !tenants.active(member.tenantId())) return null;
        members.failed(member.tenantId(),member.id(),0,null);
        return member;
    }
    @Transactional(readOnly=true)
    public ApprovalPrincipal require(String token) {
        if(token==null || !token.matches("as_[0-9a-f]{64}")) throw new SecurityException("independent approval login required");
        var session=sessions.findActive(hash(token),clock.instant()).orElseThrow(()->new SecurityException("independent approval session expired"));
        if(!tenants.active(session.tenantId())) throw new SecurityException("independent approval tenant disabled");
        var member=members.profile(session.tenantId(),session.memberId()).orElseThrow(()->new SecurityException("independent approval member disabled"));
        return new ApprovalPrincipal(session.tenantId(),member.id(),member.role(),member.email(),member.displayName(),session.csrfToken());
    }
    @Transactional(rollbackFor=Throwable.class)
    public void logout(String token) { var actor=require(token);sessions.revoke(hash(token));audit.insert(actor.tenantId(),"MEMBER",actor.memberId().toString(),"AUTH.LOGOUT",actor.memberId(),Map.of()); }
    private String secret() { byte[] bytes=new byte[32];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes); }
    private static String hash(String value) { return SigningAuthorization.hash(value.getBytes(StandardCharsets.US_ASCII)); }
    public record Login(String token,Instant expiresAt,ApprovalPrincipal principal) { }
}

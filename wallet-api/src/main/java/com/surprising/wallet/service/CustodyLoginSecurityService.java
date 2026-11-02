package com.surprising.wallet.service;

import com.surprising.wallet.common.security.Totp;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyUnauthorizedException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.custody.TenantUserRecord;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Tenant account security. Pending cookies never authorize a business operation. */
@Service
@RequiredArgsConstructor
public class CustodyLoginSecurityService {
    private final CustodyTenantUserRepository users;
    private final CustodyTenantTableRepository tenants;
    private final CustodySessionRepository sessions;
    private final CustodyAuditRepository audits;
    private final CustodyCryptoService crypto;
    private final CustodyPasswordService passwords;
    private final CustodySecurityProperties properties;
    private final CustodyVerificationMailService mail;

    public record SecurityView(UUID userId, boolean emailRequired, boolean totpRequired, boolean totpBound, boolean emailDeliveryConfigured) { }
    public record Setup(String secret, String uri, Instant expiresAt) { }
    public record EmailDelivery(String recipient, String code) { }

    public SecurityView own(CustodyPrincipal actor) { return view(member(actor, actor.actorId(), false)); }

    public List<SecurityView> list(CustodyPrincipal actor) {
        requireAdmin(actor);
        return users.listByTenant(actor.tenantId()).stream().map(u -> view(users.findById(u.id()).orElseThrow())).toList();
    }

    @Transactional(rollbackFor=Throwable.class)
    public SecurityView policy(CustodyPrincipal actor, UUID id, boolean email, boolean totp, String ip) {
        TenantUserRecord target = member(actor, id, true);
        if (email && !mail.configured()) throw new IllegalArgumentException("Configure and enable email delivery before requiring email verification");
        var before = users.security(id);
        users.setLoginPolicy(id, email, totp);
        sessions.revokeByUser(actor.tenantId(), id);
        audit(actor.tenantId(), actor.actorId(), "SECURITY.LOGIN_POLICY_CHANGED", id, ip,
                "{\"previousEmailRequired\":"+before.emailRequired()+",\"previousTotpRequired\":"+before.totpRequired()+",\"emailRequired\":"+email+",\"totpRequired\":"+totp+"}");
        return view(target);
    }

    @Transactional(rollbackFor=Throwable.class)
    public void reset(CustodyPrincipal actor, UUID id, String reason, String ip) {
        member(actor,id,true);
        if (id.equals(actor.actorId())) throw new IllegalArgumentException("Use account security to unbind your own authenticator");
        if (reason == null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("A reset reason of at most 500 characters is required");
        users.setTotp(id,null,-1);
        sessions.revokeByUser(actor.tenantId(),id);
        // JSON escaping, with no credentials or seeds in the audit payload.
        String details = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("reason",reason));
        audit(actor.tenantId(),actor.actorId(),"SECURITY.TOTP_RESET",id,ip,details);
    }

    /** Recovery of a tenant administrator stays with the platform authority, never another tenant member. */
    @Transactional(rollbackFor=Throwable.class)
    public void recoverAdministrator(CustodyPrincipal actor, UUID tenant, UUID id, String reason, String ip) {
        var platform=users.findById(actor.actorId()).orElseThrow(() -> new CustodyForbiddenException("Platform administrator required"));
        if(actor.actorType()!=CustodyPrincipal.ActorType.PLATFORM_USER || actor.tenantId()!=null || platform.tenantId()!=null
                || !"PLATFORM_ADMIN".equals(platform.role()) || !"ACTIVE".equals(platform.status())) throw new CustodyForbiddenException("Platform administrator required");
        var target=users.lockMember(tenant,id).orElseThrow(() -> new CustodyForbiddenException("Administrator unavailable"));
        if(!"TENANT_ADMIN".equals(target.role())) throw new CustodyForbiddenException("Tenant administrator required");
        if(reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("A reset reason of at most 500 characters is required");
        users.setTotp(id,null,-1);
        sessions.revokeByUser(tenant,id);
        audits.insert(UUID.randomUUID(),tenant,"PLATFORM_USER",actor.actorId().toString(),"SECURITY.ADMIN_TOTP_RESET","USER",id.toString(),ip,
                new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("reason",reason)));
    }

    @Transactional(rollbackFor=Throwable.class)
    public EmailDelivery prepareEmail(String token, String ip) {
        var context = locked(token);
        var state = context.session();
        if (state.verified() || !state.emailRequired() || state.emailVerified()) throw new IllegalArgumentException("No email verification is pending");
        if (!mail.configured()) throw new IllegalArgumentException("Email delivery is not configured");
        if (sessions.emailRecentlySent(context.user().id())) throw new IllegalArgumentException("Wait 60 seconds before requesting another code");
        String code = String.format(Locale.ROOT,"%06d", new SecureRandom().nextInt(1_000_000));
        sessions.emailCode(state.id(),crypto.sha256(token+":"+code));
        audit(context.user().tenantId(),context.user().id(),"AUTH.EMAIL_CODE_REQUESTED",context.user().id(),ip,"{}");
        return new EmailDelivery(context.user().email(),code);
    }

    @Transactional(rollbackFor=Throwable.class)
    public void deliveryFailed(String token, String ip) {
        var state=sessions.authState(crypto.sha256(token),false);
        sessions.revoke(crypto.sha256(token));
        audit(state.tenantId(),state.userId(),"AUTH.EMAIL_DELIVERY_FAILED",state.userId(),ip,"{}");
    }

    @Transactional(rollbackFor=Throwable.class, noRollbackFor=CustodyUnauthorizedException.class)
    public Setup setup(String token, String password, boolean authenticated, String ip) {
        var context=locked(token);
        var state=context.session();
        if (state.verified()!=authenticated) throw new CustodyUnauthorizedException("Invalid verification phase");
        if (authenticated && !passwords.verify(password,context.user().passwordHash())) fail(context,ip);
        if (!authenticated && state.emailRequired() && !state.emailVerified()) throw new IllegalArgumentException("Verify your email before binding an authenticator");
        if (context.security().ciphertext()!=null) throw new IllegalArgumentException("An authenticator is already bound");
        String secret;
        Instant expiry;
        if(state.enrollment()!=null && state.enrollmentExpiry().isAfter(Instant.now())) {
            secret=decode(state.enrollment(),context.user().id()+"|"+state.id()+"|");
            expiry=state.enrollmentExpiry();
        } else {
            secret=Totp.newSecret(); expiry=Instant.now().plusSeconds(300);
            sessions.enrollment(state.id(),crypto.encrypt(context.user().id()+"|"+state.id()+"|"+secret));
            audit(state.tenantId(),state.userId(),"SECURITY.TOTP_SETUP_STARTED",state.userId(),ip,"{}");
        }
        return new Setup(secret,Totp.uri("Surprising Wallet",context.user().email(),secret),expiry);
    }

    @Transactional(rollbackFor=Throwable.class, noRollbackFor=CustodyUnauthorizedException.class)
    public CustodyAuthService.LoginResult verify(String token, String emailCode, String totpCode, String ip) {
        var context=locked(token);
        var state=context.session();
        if (state.verified()) throw new IllegalArgumentException("Login verification is already complete");
        boolean emailVerified=state.emailVerified();
        if(state.emailRequired() && !emailVerified) {
            if(emailCode==null || !emailCode.matches("[0-9]{6}") || state.emailHash()==null || state.emailExpiry()==null || !state.emailExpiry().isAfter(Instant.now())
                    || !crypto.constantTimeEquals(state.emailHash(),crypto.sha256(token+":"+emailCode))) fail(context,ip);
            sessions.emailVerified(state.id()); emailVerified=true;
        }
        if(state.totpRequired()) {
            if(totpCode==null || totpCode.isBlank()) return result(context,token,state.expiresAt(),true,emailVerified);
            verifyTotp(context,totpCode,ip,true);
        }
        String rotated="cs_"+crypto.randomSecret(32);
        Duration ttl=properties.getSessionTtl();
        if(ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(7))>0) throw new IllegalStateException("Invalid session TTL");
        Instant expiry=Instant.now().plus(ttl);
        sessions.finish(state.id(),crypto.sha256(rotated),users.security(state.userId()).version(),expiry);
        users.recordLoginSuccess(state.userId());
        audit(state.tenantId(),state.userId(),"AUTH.LOGIN",state.userId(),ip,"{}");
        return result(context,rotated,expiry,false,emailVerified);
    }

    @Transactional(rollbackFor=Throwable.class, noRollbackFor=CustodyUnauthorizedException.class)
    public SecurityView confirm(String token, String code, String ip) {
        var context=locked(token);
        if(!context.session().verified() || context.security().ciphertext()!=null) throw new IllegalArgumentException("No authenticator binding is pending");
        verifyTotp(context,code,ip,true);
        sessions.retainOnly(context.user().id(),context.session().id(),users.security(context.user().id()).version());
        return view(context.user());
    }

    @Transactional(rollbackFor=Throwable.class, noRollbackFor=CustodyUnauthorizedException.class)
    public void unbind(String token, String password, String code, String ip) {
        var context=locked(token);
        if(!context.session().verified()) throw new CustodyUnauthorizedException("Complete login verification first");
        if(context.security().totpRequired()) throw new CustodyForbiddenException("Your administrator requires an authenticator");
        if(!passwords.verify(password,context.user().passwordHash())) fail(context,ip);
        verifyTotp(context,code,ip,false);
        users.setTotp(context.user().id(),null,-1);
        sessions.retainOnly(context.user().id(),context.session().id(),users.security(context.user().id()).version());
        audit(context.user().tenantId(),context.user().id(),"SECURITY.TOTP_UNBOUND",context.user().id(),ip,"{}");
    }

    private void verifyTotp(Context c, String code, String ip, boolean allowEnrollment) {
        String secret;
        boolean enrollment=c.security().ciphertext()==null;
        if(enrollment) {
            if(!allowEnrollment || c.session().enrollment()==null || !c.session().enrollmentExpiry().isAfter(Instant.now())) throw new IllegalArgumentException("Scan a fresh authenticator QR code first");
            secret=decode(c.session().enrollment(),c.user().id()+"|"+c.session().id()+"|");
        } else secret=decode(c.security().ciphertext(),c.user().id()+"|");
        var step=Totp.verify(secret,code,Instant.now(),c.security().lastStep());
        if(step.isEmpty()) fail(c,ip);
        if(enrollment) {
            users.setTotp(c.user().id(),crypto.encrypt(c.user().id()+"|"+secret),step.orElseThrow());
            sessions.retainOnly(c.user().id(),c.session().id(),users.security(c.user().id()).version());
            audit(c.user().tenantId(),c.user().id(),"SECURITY.TOTP_BOUND",c.user().id(),ip,"{}");
        } else if(!users.acceptTotp(c.user().id(),step.orElseThrow())) fail(c,ip);
    }

    private Context locked(String token) {
        if(token==null || !token.startsWith("cs_") || token.length()<32) throw new CustodyUnauthorizedException("Session required");
        String hash=crypto.sha256(token);
        var initial=sessions.authState(hash,false);
        var user=users.lockById(initial.userId());
        var state=sessions.authState(hash,true);
        var security=users.security(user.id());
        if(user.tenantId()==null || !"ACTIVE".equals(user.status()) || !"ACTIVE".equals(tenants.findById(user.tenantId()).orElseThrow().status())
                || state.version()!=security.version() || state.attempts()>=5 || (user.lockedUntil()!=null && user.lockedUntil().isAfter(Instant.now()))) throw new CustodyUnauthorizedException("Session expired or account locked");
        return new Context(user,state,security);
    }

    private void fail(Context c, String ip) {
        sessions.failedVerification(c.session().id());
        users.recordLoginFailure(c.user().id(),c.user().failedLoginCount()+1>=5 ? Instant.now().plusSeconds(900) : null);
        audit(c.user().tenantId(),c.user().id(),"AUTH.VERIFICATION_FAILED",c.user().id(),ip,"{}");
        throw new CustodyUnauthorizedException("Invalid or expired verification code");
    }
    private String decode(String encrypted, String prefix) {
        String value=crypto.decrypt(encrypted);
        if(!value.startsWith(prefix)) throw new CustodyUnauthorizedException("Invalid authenticator binding");
        return value.substring(prefix.length());
    }
    private CustodyAuthService.LoginResult result(Context c,String token,Instant expiry,boolean pending,boolean emailVerified) {
        var u=c.user();
        return new CustodyAuthService.LoginResult(token,expiry,u.id(),u.tenantId(),tenants.findById(u.tenantId()).orElseThrow().slug(),u.email(),u.displayName(),u.role(),
                CustodyAuthService.consoleScopes(u.role()),pending,c.session().emailRequired(),emailVerified,c.session().totpRequired(),pending && users.security(u.id()).ciphertext()==null && c.session().totpRequired());
    }
    private TenantUserRecord member(CustodyPrincipal actor, UUID id, boolean management) {
        if(actor.actorType()!=CustodyPrincipal.ActorType.TENANT_USER || actor.tenantId()==null) throw new CustodyForbiddenException("Tenant account required");
        if(management) requireAdmin(actor);
        var user=management ? users.lockMember(actor.tenantId(),id).orElseThrow(() -> new CustodyForbiddenException("Member unavailable")) : users.findById(id).orElseThrow();
        if(!actor.tenantId().equals(user.tenantId()) || (!id.equals(actor.actorId()) && "TENANT_ADMIN".equals(user.role()))) throw new CustodyForbiddenException("Account is outside your management scope");
        return user;
    }
    private void requireAdmin(CustodyPrincipal actor) {
        var user=users.findById(actor.actorId()).orElseThrow();
        if(actor.actorType()!=CustodyPrincipal.ActorType.TENANT_USER || actor.tenantId()==null || !actor.tenantId().equals(user.tenantId()) || !"TENANT_ADMIN".equals(user.role()) || !"ACTIVE".equals(user.status())) throw new CustodyForbiddenException("Tenant administrator required");
    }
    private SecurityView view(TenantUserRecord user) { var s=users.security(user.id()); return new SecurityView(user.id(),s.emailRequired(),s.totpRequired(),s.ciphertext()!=null,mail.configured()); }
    private void audit(UUID tenant,UUID actor,String action,UUID target,String ip,String details) { audits.insert(UUID.randomUUID(),tenant,"TENANT_USER",actor.toString(),action,"USER",target.toString(),ip,details); }
    private record Context(TenantUserRecord user,CustodySessionRepository.AuthState session,CustodyTenantUserRepository.SecurityState security) { }
}

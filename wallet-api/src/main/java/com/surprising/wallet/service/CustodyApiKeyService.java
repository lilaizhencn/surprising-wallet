package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyUnauthorizedException;
import com.surprising.wallet.model.CidrMatcher;
import com.surprising.wallet.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.custody.ApiKeyRecord;
import com.surprising.wallet.model.custody.ApiKeyView;
import com.surprising.wallet.repository.CustodyApiKeyRepository;
import com.surprising.wallet.repository.CustodyApiNonceRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyIpRuleRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 租户 API Key 管理与 HMAC 验签；Secret 仅在创建时返回。 */
@Service
@RequiredArgsConstructor
public class CustodyApiKeyService {
    private final CustodyApiKeyRepository keys;
    private final CustodyTenantTableRepository tenants;
    private final CustodyIpRuleRepository ipRules;
    private final CustodyApiNonceRepository nonces;
    private final CustodyAuditRepository audits;
    private final CustodyCryptoService crypto;
    private final CustodySecurityProperties properties;

    @Transactional(rollbackFor = Throwable.class)
    public CreatedApiKey create(UUID tenantId, UUID actorId, String name, String sourceIp) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("API key name is required and must not exceed 120 characters");
        }
        UUID id = UUID.randomUUID();
        String keyId = "swk_" + crypto.randomSecret(18);
        String secret = "sws_" + crypto.randomSecret(32);
        ApiKeyRecord saved = keys.insert(
                id, tenantId, keyId, normalizedName, crypto.encrypt(secret), actorId);
        audits.insert(UUID.randomUUID(), tenantId, ActorType.TENANT_USER.name(), actorId.toString(), "API_KEY.CREATE",
                "API_KEY", id.toString(), sourceIp, "{\"keyId\":\"" + keyId + "\"}");
        return new CreatedApiKey(saved.id(), keyId, secret, saved.name(), saved.createdAt());
    }

    public List<ApiKeyView> list(UUID tenantId) {
        return keys.listByTenant(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void revoke(UUID tenantId, UUID actorId, UUID keyId, String sourceIp) {
        if (keys.revoke(tenantId, keyId) != 1) {
            throw new IllegalArgumentException("active API key not found");
        }
        audits.insert(UUID.randomUUID(), tenantId, ActorType.TENANT_USER.name(), actorId.toString(), "API_KEY.REVOKE",
                "API_KEY", keyId.toString(), sourceIp, "{}");
    }

    public CustodyPrincipal authenticate(String keyId, long timestampSeconds, String nonce,
                                         String signature, String method, String requestTarget,
                                         byte[] body, String sourceIp) {
        long now = Instant.now().getEpochSecond();
        long allowedSkew = properties.getApiClockSkew().toSeconds();
        if (allowedSkew < 1 || allowedSkew > 3_600) {
            throw new IllegalStateException("custody API clock skew must be between 1 second and 1 hour");
        }
        if (timestampSeconds < now - allowedSkew || timestampSeconds > now + allowedSkew) {
            throw new CustodyUnauthorizedException("request timestamp is outside the allowed clock skew");
        }
        if (nonce == null || !nonce.matches("^[A-Za-z0-9_-]{16,128}$")) {
            throw new CustodyUnauthorizedException("invalid request nonce");
        }
        ApiKeyRecord credential = keys.findActiveByKeyId(keyId)
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid API key"));
        var tenant = tenants.findById(credential.tenantId())
                .orElseThrow(() -> new CustodyForbiddenException("tenant is not active"));
        if (!"ACTIVE".equals(tenant.status())) {
            throw new CustodyForbiddenException("tenant is not active");
        }
        if (tenant.ipAllowlistEnabled()) {
            List<String> rules = ipRules.listActive(credential.tenantId());
            boolean allowed = rules.stream().anyMatch(cidr -> CidrMatcher.matches(cidr, sourceIp));
            if (!allowed) {
                throw new CustodyForbiddenException("source IP is not allowed");
            }
        }
        String canonical = canonicalRequest(timestampSeconds, nonce, method, requestTarget, body);
        String secret = crypto.decrypt(credential.secretCiphertext());
        String expected = crypto.hmacSha256(secret, canonical);
        if (!crypto.constantTimeEquals(expected, signature)) {
            throw new CustodyUnauthorizedException("invalid request signature");
        }
        if (!nonces.reserve(keyId, nonce, Instant.now().plusSeconds(allowedSkew * 2))) {
            throw new CustodyUnauthorizedException("request nonce has already been used");
        }
        keys.touch(credential.id(), sourceIp);
        return new CustodyPrincipal(
                ActorType.API_KEY,
                credential.id(),
                credential.tenantId(),
                tenant.slug(),
                "API_KEY",
                Set.of("*"));
    }

    public String canonicalRequest(long timestampSeconds, String nonce, String method,
                                   String requestTarget, byte[] body) {
        return timestampSeconds + "\n"
                + nonce + "\n"
                + method.toUpperCase(Locale.ROOT) + "\n"
                + requestTarget + "\n"
                + crypto.sha256(body == null ? new byte[0] : body);
    }

    public record CreatedApiKey(
            UUID id,
            String keyId,
            String secret,
            String name,
            Instant createdAt
    ) {
    }
}

package com.surprising.wallet.custody.service;

import com.surprising.wallet.custody.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.custody.repository.CustodyRepository.ApiKeyRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.surprising.wallet.custody.model.CidrMatcher;
import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodySecurityProperties;
import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

@Service
public class CustodyApiKeyService {
    private final CustodyRepository repository;
    private final CustodyCryptoService crypto;
    private final CustodySecurityProperties properties;
    public CustodyApiKeyService(CustodyRepository repository, CustodyCryptoService crypto,
                                CustodySecurityProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.properties = properties;
    }

    @Transactional(rollbackFor = Throwable.class)
    public CreatedApiKey create(UUID tenantId, UUID actorId, String name, String sourceIp) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("API key name is required and must not exceed 120 characters");
        }
        UUID id = UUID.randomUUID();
        String keyId = "swk_" + crypto.randomSecret(18);
        String secret = "sws_" + crypto.randomSecret(32);
        ApiKeyRecord saved = repository.insertApiKey(
                id, tenantId, keyId, normalizedName, crypto.encrypt(secret), actorId);
        repository.audit(tenantId, ActorType.TENANT_USER.name(), actorId.toString(), "API_KEY.CREATE",
                "API_KEY", id.toString(), sourceIp, "{\"keyId\":\"" + keyId + "\"}");
        return new CreatedApiKey(saved.id(), keyId, secret, saved.name(), saved.createdAt());
    }
    public List<Map<String, Object>> list(UUID tenantId) {
        return repository.listApiKeys(tenantId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void revoke(UUID tenantId, UUID actorId, UUID keyId, String sourceIp) {
        repository.revokeApiKey(tenantId, keyId);
        repository.audit(tenantId, ActorType.TENANT_USER.name(), actorId.toString(), "API_KEY.REVOKE",
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
        if (Math.abs(now - timestampSeconds) > allowedSkew) {
            throw new CustodyUnauthorizedException("request timestamp is outside the allowed clock skew");
        }
        if (nonce == null || !nonce.matches("^[A-Za-z0-9_-]{16,128}$")) {
            throw new CustodyUnauthorizedException("invalid request nonce");
        }
        ApiKeyRecord credential = repository.findActiveApiKey(keyId)
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid API key"));
        if (!"ACTIVE".equals(credential.tenantStatus())) {
            throw new CustodyForbiddenException("tenant is not active");
        }
        if (credential.ipAllowlistEnabled()) {
            List<String> rules = repository.activeIpRules(credential.tenantId());
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
        if (!repository.reserveNonce(keyId, nonce, Instant.now().plusSeconds(allowedSkew * 2))) {
            throw new CustodyUnauthorizedException("request nonce has already been used");
        }
        repository.touchApiKey(credential.id(), sourceIp);
        return new CustodyPrincipal(
                ActorType.API_KEY,
                credential.id(),
                credential.tenantId(),
                credential.tenantSlug(),
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

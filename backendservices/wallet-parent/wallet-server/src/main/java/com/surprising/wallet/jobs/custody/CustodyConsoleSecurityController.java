package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleSecurityController {
    private final CustodyRepository repository;
    private final CustodyApiKeyService apiKeys;

    public CustodyConsoleSecurityController(CustodyRepository repository, CustodyApiKeyService apiKeys) {
        this.repository = repository;
        this.apiKeys = apiKeys;
    }

    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys(HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        return apiKeys.list(principal.tenantId());
    }

    @PostMapping("/api-keys")
    public CustodyApiKeyService.CreatedApiKey createApiKey(@RequestBody CreateApiKeyRequest body,
                                                           HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        return apiKeys.create(principal.tenantId(), principal.actorId(), body.name(), body.scopes(),
                CustodyRequestSupport.clientIp(request));
    }

    @DeleteMapping("/api-keys/{apiKeyId}")
    public Map<String, Object> revokeApiKey(@PathVariable UUID apiKeyId, HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        apiKeys.revoke(principal.tenantId(), principal.actorId(), apiKeyId,
                CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    @GetMapping("/ip-allowlist")
    public Map<String, Object> ipAllowlist(HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        CustodyRepository.TenantRecord tenant = repository.requireTenant(principal.tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", tenant.ipAllowlistEnabled());
        result.put("rules", repository.listIpRules(principal.tenantId()));
        return result;
    }

    @Transactional(rollbackFor = Throwable.class)
    @PutMapping("/ip-allowlist/enforcement")
    public Map<String, Object> ipEnforcement(@RequestBody IpEnforcementRequest body,
                                             HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        repository.setIpAllowlistEnabled(principal.tenantId(), body.enabled());
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.ENFORCEMENT_CHANGE", "TENANT", principal.tenantId().toString(),
                CustodyRequestSupport.clientIp(request), "{\"enabled\":" + body.enabled() + "}");
        return ipAllowlist(request);
    }

    @Transactional(rollbackFor = Throwable.class)
    @PostMapping("/ip-allowlist/rules")
    public Map<String, Object> addIpRule(@RequestBody CreateIpRuleRequest body,
                                        HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        String label = requireText(body.label(), "IP rule label", 120);
        String cidr = requireText(body.cidr(), "CIDR", 64);
        UUID ruleId = UUID.randomUUID();
        Map<String, Object> created = repository.insertIpRule(
                principal.tenantId(), ruleId, label, cidr, principal.actorId());
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.RULE_CREATE", "IP_RULE", ruleId.toString(),
                CustodyRequestSupport.clientIp(request), "{\"cidr\":\"" + cidr + "\"}");
        return created;
    }

    @Transactional(rollbackFor = Throwable.class)
    @DeleteMapping("/ip-allowlist/rules/{ruleId}")
    public Map<String, Object> deleteIpRule(@PathVariable UUID ruleId, HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        repository.deleteIpRule(principal.tenantId(), ruleId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.RULE_DELETE", "IP_RULE", ruleId.toString(),
                CustodyRequestSupport.clientIp(request), "{}");
        return Map.of("ok", true);
    }

    @GetMapping("/audit-log")
    public List<Map<String, Object>> audit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        if (!principal.hasScope("audit:read") && !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("audit:read scope required");
        }
        return repository.listAudit(principal.tenantId(), limit, offset);
    }

    private static CustodyPrincipal requireTenantAdmin(HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        if (!"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
        return principal;
    }

    private static String requireText(String value, String field, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return result;
    }

    public record CreateApiKeyRequest(String name, Set<String> scopes) {
    }

    public record IpEnforcementRequest(boolean enabled) {
    }

    public record CreateIpRuleRequest(String label, String cidr) {
    }
}

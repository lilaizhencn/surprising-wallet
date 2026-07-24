package com.surprising.wallet.custody.controller.console;

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
import java.util.UUID;

import com.surprising.wallet.custody.service.CustodyApiKeyService;
import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

/**
 * Console 安全配置控制器。
 *
 * <p>端点路径：/custody/console/v1/{tenantId}/security。
 * 提供 API Key 管理（创建/列表/撤销）和 IP 白名单配置功能。
 */
@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleSecurityController {
    /** 仓储服务，用于安全配置、审计与白名单持久化。 */
    private final CustodyRepository repository;
    /** API Key 管理服务。 */
    private final CustodyApiKeyService apiKeys;

    /**
     * 注入仓储与 API Key 服务。
     */
    public CustodyConsoleSecurityController(CustodyRepository repository, CustodyApiKeyService apiKeys) {
        this.repository = repository;
        this.apiKeys = apiKeys;
    }

    /**
     * 查询当前租户的所有 API Key。
     */
    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys(HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        return apiKeys.list(principal.tenantId());
    }

    /**
     * 创建 API Key，返回新 key 及元数据信息。
     */
    @PostMapping("/api-keys")
    public CustodyApiKeyService.CreatedApiKey createApiKey(@RequestBody CreateApiKeyRequest body,
                                                           HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        return apiKeys.create(principal.tenantId(), principal.actorId(), body.name(),
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 撤销单个 API Key，后续请求立即失效。
     */
    @DeleteMapping("/api-keys/{apiKeyId}")
    public Map<String, Object> revokeApiKey(@PathVariable UUID apiKeyId, HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        apiKeys.revoke(principal.tenantId(), principal.actorId(), apiKeyId,
                CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    /**
     * 查询 IP 白名单开关与规则列表。
     */
    @GetMapping("/ip-allowlist")
    public Map<String, Object> ipAllowlist(HttpServletRequest request) {
        CustodyPrincipal principal = requireTenantAdmin(request);
        CustodyRepository.TenantRecord tenant = repository.requireTenant(principal.tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", tenant.ipAllowlistEnabled());
        result.put("rules", repository.listIpRules(principal.tenantId()));
        return result;
    }

    /**
     * 更新白名单生效策略并记录审计。
     */
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

    /**
     * 新增 IP 白名单规则，并返回持久化后的记录。
     */
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

    /**
     * 删除白名单规则并记录审计变更。
     */
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

    /**
     * 查询审计日志，需具备 audit:read 或 TENANT_ADMIN。
     */
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

    /**
     * 断言调用者是租户管理员。
     */
    private static CustodyPrincipal requireTenantAdmin(HttpServletRequest request) {
        CustodyPrincipal principal = CustodyRequestSupport.requirePrincipal(request);
        if (!"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
        return principal;
    }

    /**
     * 校验文本参数非空且不超过长度限制。
     */
    private static String requireText(String value, String field, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return result;
    }

    /**
     * API Key 创建请求。
     */
    public record CreateApiKeyRequest(String name) {
    }

    /**
     * 白名单生效开关请求。
     */
    public record IpEnforcementRequest(boolean enabled) {
    }

    /**
     * 白名单规则创建请求。
     */
    public record CreateIpRuleRequest(String label, String cidr) {
    }
}

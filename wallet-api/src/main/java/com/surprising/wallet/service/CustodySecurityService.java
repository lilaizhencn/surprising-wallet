package com.surprising.wallet.service;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.PageView;
import com.surprising.wallet.repository.CustodyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 托管安全配置应用服务，负责 IP 白名单和租户审计日志。
 *
 * <p>Controller 只负责 HTTP 参数和身份提取，本服务统一执行权限校验、输入校验、
 * 数据写入和安全审计，避免 Web 层直接访问 Repository。</p>
 */
@Service
public class CustodySecurityService {
    /** 托管数据仓储。 */
    private final CustodyRepository repository;

    /**
     * 构造安全配置服务。
     *
     * @param repository 托管数据仓储
     */
    public CustodySecurityService(CustodyRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询租户 IP 白名单配置。
     *
     * @param principal 当前租户身份
     * @return 白名单开关和规则
     */
    public Map<String, Object> ipAllowlist(CustodyPrincipal principal) {
        requireTenantAdmin(principal);
        CustodyRepository.TenantRecord tenant = repository.requireTenant(principal.tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", tenant.ipAllowlistEnabled());
        result.put("rules", repository.listIpRules(principal.tenantId()));
        return result;
    }

    /**
     * 更新 IP 白名单开关并记录审计。
     *
     * @param principal 当前租户身份
     * @param enabled 是否启用
     * @param sourceIp 请求来源地址
     * @return 更新后的白名单配置
     */
    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> updateIpEnforcement(
            CustodyPrincipal principal, boolean enabled, String sourceIp) {
        requireTenantAdmin(principal);
        repository.setIpAllowlistEnabled(principal.tenantId(), enabled);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.ENFORCEMENT_CHANGE", "TENANT", principal.tenantId().toString(),
                sourceIp, "{\"enabled\":" + enabled + "}");
        return ipAllowlist(principal);
    }

    /**
     * 新增 IP 白名单规则并记录审计。
     *
     * @param principal 当前租户身份
     * @param label 规则名称
     * @param cidr CIDR 地址段
     * @param sourceIp 请求来源地址
     * @return 新建规则
     */
    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> addIpRule(
            CustodyPrincipal principal, String label, String cidr, String sourceIp) {
        requireTenantAdmin(principal);
        String normalizedLabel = required(label, "IP rule label", 120);
        String normalizedCidr = required(cidr, "CIDR", 64);
        UUID ruleId = UUID.randomUUID();
        Map<String, Object> created = repository.insertIpRule(
                principal.tenantId(), ruleId, normalizedLabel, normalizedCidr, principal.actorId());
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.RULE_CREATE", "IP_RULE", ruleId.toString(), sourceIp,
                "{\"cidr\":\"" + normalizedCidr + "\"}");
        return created;
    }

    /**
     * 删除 IP 白名单规则并记录审计。
     *
     * @param principal 当前租户身份
     * @param ruleId 规则 ID
     * @param sourceIp 请求来源地址
     * @return 操作结果
     */
    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> deleteIpRule(
            CustodyPrincipal principal, UUID ruleId, String sourceIp) {
        requireTenantAdmin(principal);
        repository.deleteIpRule(principal.tenantId(), ruleId);
        repository.audit(principal.tenantId(), "TENANT_USER", principal.actorId().toString(),
                "IP_ALLOWLIST.RULE_DELETE", "IP_RULE", ruleId.toString(), sourceIp, "{}");
        return Map.of("ok", true);
    }

    /**
     * 分页查询租户审计日志。
     *
     * @param principal 当前租户身份
     * @param limit 分页大小
     * @param offset 偏移量
     * @return 审计日志分页
     */
    public PageView<Map<String, Object>> audit(
            CustodyPrincipal principal, int limit, int offset) {
        if (principal == null || principal.tenantId() == null
                || (!principal.hasScope("audit:read") && !"TENANT_ADMIN".equals(principal.role()))) {
            throw new CustodyForbiddenException("audit:read scope required");
        }
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(
                repository.listAudit(principal.tenantId(), pageSize, pageOffset),
                repository.countAudit(principal.tenantId()), pageSize, pageOffset);
    }

    /** 校验当前身份是租户管理员。 */
    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null
                || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }

    /** 校验文本参数。 */
    private static String required(String value, String field, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " is required and must not exceed " + maxLength + " characters");
        }
        return result;
    }
}

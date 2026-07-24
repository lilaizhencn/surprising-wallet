package com.surprising.wallet.custody.model;

import java.util.Set;
import java.util.UUID;

/**
 * 托管认证主体，记录当前请求的认证身份和权限信息。
 *
 * <p>认证后的请求在 Filter 中解析出主体信息并注入请求上下文，后续的 Controller/Service
 * 通过该主体判断租户归属、角色和权限 scope。
 *
 * @param actorType  认证类型
 * @param actorId    认证主体唯一标识
 * @param tenantId   所属租户 ID（PLATFORM_USER 时可能为 null）
 * @param tenantSlug 租户 short name / URL 标识
 * @param role       租户内角色（如 TENANT_ADMIN）
 * @param scopes     已授予的权限范围（"*" 表示全部权限）
 */
public record CustodyPrincipal(
        ActorType actorType,
        UUID actorId,
        UUID tenantId,
        String tenantSlug,
        String role,
        Set<String> scopes
) {

    /** 认证主体类型 */
    public enum ActorType {
        /** 平台管理员（Platform 后台登录） */
        PLATFORM_USER,
        /** 租户用户（Console 登录） */
        TENANT_USER,
        /** API 密钥（HMAC 签名认证） */
        API_KEY
    }

    /**
     * 判断是否为平台管理员（PLATFORM_USER + PLATFORM_ADMIN 角色）。
     *
     * @return true 如果具有平台管理员权限
     */
    public boolean isPlatformAdmin() {
        return actorType == ActorType.PLATFORM_USER && "PLATFORM_ADMIN".equals(role);
    }

    /**
     * 判断是否拥有指定的权限 scope。
     *
     * <p>scope 为 "*" 表示全部权限通配。
     *
     * @param scope 权限名称
     * @return true 如果拥有该权限
     */
    public boolean hasScope(String scope) {
        return scopes != null && (scopes.contains("*") || scopes.contains(scope));
    }
}

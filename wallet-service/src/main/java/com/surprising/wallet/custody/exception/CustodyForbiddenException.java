package com.surprising.wallet.custody.exception;
/**
 * 托管权限不足异常，表示认证主体缺乏执行操作所需的 scope 或角色。
 *
 * <p>例如 API Key 缺少 withdrawals:write scope、Console 用户非 TENANT_ADMIN 角色等场景。
 * 被 {@code CustodyExceptionHandler} 统一转为 HTTP 403 响应。
 */
public class CustodyForbiddenException extends RuntimeException {
    public CustodyForbiddenException(String message) {
        super(message);
    }
}

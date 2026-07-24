package com.surprising.wallet.custody.exception;
/**
 * 托管未授权异常，表示 API 签名无效、Session 过期或缺少认证凭据。
 *
 * <p>由 {@code CustodyApiAuthenticationFilter} 和 {@code CustodyConsoleAuthenticationFilter}
 * 在认证失败时抛出，被 {@code CustodyExceptionHandler} 统一转为 HTTP 401 响应。
 */
public class CustodyUnauthorizedException extends RuntimeException {
    public CustodyUnauthorizedException(String message) {
        super(message);
    }
}

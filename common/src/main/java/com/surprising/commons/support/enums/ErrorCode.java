package com.surprising.commons.support.enums;

/**
 * 错误码接口，定义获取错误码和错误消息的标准方法。
 *
 * <p>所有错误码枚举（如 {@link SystemErrorCode}）应实现此接口，以确保
 * 错误码体系的统一性和可扩展性。</p>
 *
 * @see SystemErrorCode
 */
public interface ErrorCode {
    int getCode();
    String getMessage();
}

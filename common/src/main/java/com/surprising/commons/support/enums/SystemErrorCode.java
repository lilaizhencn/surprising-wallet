package com.surprising.commons.support.enums;

/**
 * 系统错误码枚举，实现 {@link ErrorCode} 接口，定义系统级通用错误码。
 *
 * <ul>
 *   <li>{@link #SUCCESS} - 操作成功，错误码 0</li>
 *   <li>{@link #UNKNOWN_ERROR} - 未知错误，错误码 50000</li>
 * </ul>
 *
 * @see ErrorCode
 * @see ResultUtils
 */
public enum SystemErrorCode implements ErrorCode {
    UNKNOWN_ERROR(50000, "unknown error"),
    SUCCESS(0, "success");

    private final int code;
    private final String message;

    SystemErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

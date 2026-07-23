package com.surprising.commons.support.enums;

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

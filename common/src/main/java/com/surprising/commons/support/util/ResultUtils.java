package com.surprising.commons.support.util;

import com.surprising.commons.support.enums.SystemErrorCode;
import com.surprising.commons.support.model.ResponseResult;

public class ResultUtils {

    public static <T> ResponseResult<T> success(T data) {
        return new ResponseResult<>(SystemErrorCode.SUCCESS.getCode(), SystemErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> ResponseResult<T> success() {
        return new ResponseResult<>(SystemErrorCode.SUCCESS.getCode(), SystemErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> ResponseResult<T> failure(String message) {
        return new ResponseResult<>(SystemErrorCode.UNKNOWN_ERROR.getCode(), message, null);
    }

    public static <T> ResponseResult<T> failure(SystemErrorCode errorCode) {
        return new ResponseResult<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}

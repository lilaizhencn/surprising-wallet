package com.surprising.commons.support.util;

import com.surprising.commons.support.enums.SystemErrorCode;
import com.surprising.commons.support.model.ResponseResult;

/**
 * 通用响应结果工具类，提供快速构建 {@link ResponseResult} 的静态方法。
 *
 * <h3>主要方法：</h3>
 * <ul>
 *   <li>{@link #success(Object)} - 构建带数据的成功响应</li>
 *   <li>{@link #success()} - 构建无数据的成功响应</li>
 *   <li>{@link #failure(String)} - 构建带自定义消息的失败响应</li>
 *   <li>{@link #failure(SystemErrorCode)} - 构建指定错误码的失败响应</li>
 * </ul>
 *
 * @see ResponseResult
 * @see SystemErrorCode
 */
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

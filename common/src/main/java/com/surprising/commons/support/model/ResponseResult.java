package com.surprising.commons.support.model;

/**
 * 通用响应结果模型，封装接口返回的标准数据结构。
 *
 * <p>包含三个核心字段：</p>
 * <ul>
 *   <li>{@code code} - 状态码，用于标识请求处理结果</li>
 *   <li>{@code message} - 响应消息，描述处理结果</li>
 *   <li>{@code data} - 响应体数据，泛型类型，可为空</li>
 * </ul>
 *
 * @param <T> 响应数据的类型
 * @see ResultUtils
 * @see SystemErrorCode
 */
public class ResponseResult<T> {
    private int code;
    private String message;
    private T data;

    public ResponseResult() {
    }

    public ResponseResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}

package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 常量枚举，定义系统中使用的各种常量标识。
 *
 * <ul>
 *   <li>{@link #LOGIN_TOKEN_APP_PRE} - APP Token 前缀，用于标识登录令牌</li>
 *   <li>{@link #LOGIN_TOKEN_APP_TIME_PRE} - APP 登录记录时间的前缀</li>
 * </ul>
 */
@AllArgsConstructor
public enum ConstEnum {
    /**
     * APP TOKEN前缀
     */
    LOGIN_TOKEN_APP_PRE("appToken"),
    /**
     * APP 登录记录时间的前缀
     */
    LOGIN_TOKEN_APP_TIME_PRE("appTime");
    @Setter
    @Getter
    private String name;
}

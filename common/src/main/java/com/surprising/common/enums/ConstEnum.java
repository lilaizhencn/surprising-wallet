package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

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

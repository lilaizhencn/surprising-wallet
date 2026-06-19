package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */
@AllArgsConstructor
public enum VerifyTypeEnum {

    /**
     * 用户性别 1.男  2.女
     */
    EMAIL(1, "邮箱"),

    SMS(2, "短信"),

    GOOGLE(3, "google验证器"),
    ;
    @Setter
    @Getter
    private int value;

    @Setter
    @Getter
    private String name;

}

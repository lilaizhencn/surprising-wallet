package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */
@AllArgsConstructor
public enum GenderEnum {

    /**
     * 用户性别 1.男  2.女
     */
    UNKNOWN(0, "未知"),

    MALE(1, "男"),

    FEMALE(2, "女"),
    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

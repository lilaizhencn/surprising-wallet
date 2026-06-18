package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum EmailSetEnum {

    /**
     * 邮箱设置标示 0. 未设置 1. 已设置
     */
    NO_SET(0, "未设置"),
    SETED(1, "已设置"),

    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

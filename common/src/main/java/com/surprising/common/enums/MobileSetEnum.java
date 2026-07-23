package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum MobileSetEnum {

    /**
     * 手机号设置标示 0. 未设置 1. 已设置
     */
    NOSET(0, "未设置"),
    SETED(1, "已设置"),

    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

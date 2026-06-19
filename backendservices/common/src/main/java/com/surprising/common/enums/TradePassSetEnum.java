package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum TradePassSetEnum {
    /**
     * 交易密码设置标示 0. 未设置 1. 已设置 2.已开启
     */
    NOSET(0, "未设置 默认"),
    SETED(1, "已设置"),
    OPEN(2, "已开启"),

    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum GoogleCodeSetEnum {
    /**
     * 用户谷歌绑定状态 0.未绑定 1.绑定
     */
    NOSET(0, "未绑定"),
    OK(1, "绑定"),
    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

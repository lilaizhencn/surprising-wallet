package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录来源
 *
 * @author lilaizhen
 */

@AllArgsConstructor
public enum LoginSourceEnum {

    /**
     * 登录来源 1web 2app
     */
    LOGIN_WEB(1, "web"),
    LOGIN_APP(2, "app"),
    LOGIN_API(3, "open-api");
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

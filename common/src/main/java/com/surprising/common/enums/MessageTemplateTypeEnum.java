package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum MessageTemplateTypeEnum {
    /**
     * 用户注册模板
     */
    USER_REGISTER(1, "user-register"),
    ;
    @Setter
    @Getter
    private int id;

    @Setter
    @Getter
    private String type;
}

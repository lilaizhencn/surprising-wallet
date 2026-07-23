package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum AuthLevelEnum {
    /**
     * 用户等级 0未认证 1.普通 2.中级 3.高级
     */

    NO_AUTH(0, "未认证 默认"),
    MOBILE_OR_EMAIL(1, "手机号或邮箱"),
    ID_CARD(2, "身份证或护照"),
    VIDEO(3, "视频认证"),

    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;

    public static AuthLevelEnum parse(int status) {
        for (AuthLevelEnum i : AuthLevelEnum.values()) {
            if (i.getStatus() == status) {
                return i;
            }
        }
        return null;
    }
}

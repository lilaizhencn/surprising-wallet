package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum UserStatusEnum {

    /**
     * 用户状态 1正常 -1删除 -2禁用
     */
    OK(1, "正常"),
    DELETE(-1, "禁用"),
    FORBIDDEN(-2, "禁用"),
    ;

    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;

    public static UserStatusEnum parse(int status) {
        for (UserStatusEnum i : UserStatusEnum.values()) {
            if (i.getStatus() == status) {
                return i;
            }
        }
        return null;
    }
}

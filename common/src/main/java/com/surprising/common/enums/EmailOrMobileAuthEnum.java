package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum EmailOrMobileAuthEnum {


    /**
     * 初级认证状态  -1：认证被拒绝 0：未提交认证资料 1：待认证 2：认证通过
     */
    INIT(0, "未提交资料"),
    WAIT(1, "已提交资料，待认证"),
    OK(2, "认证通过"),
    REFUSE(3, "认证被拒绝"),

    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;

    public static EmailOrMobileAuthEnum parse(int status) {
        for (EmailOrMobileAuthEnum i : EmailOrMobileAuthEnum.values()) {
            if (i.getStatus() == status) {
                return i;
            }
        }
        return null;
    }
}

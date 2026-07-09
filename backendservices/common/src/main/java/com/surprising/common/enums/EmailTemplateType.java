package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */
@AllArgsConstructor
public enum EmailTemplateType {
    /**
     * 邮箱注册
     */
    REGISTER(1, "email-register"),
    /**
     * 提现确认
     */
    WITHDRAW_CONFRIM(2, "withdraw-confirm"),
    /**
     * 异地登录提醒
     */
    IP_CHANGE_NOTIFY(3, "ip-change-notify"),

    ;
    @Setter
    @Getter
    private int type;

    @Setter
    @Getter
    private String template;

}

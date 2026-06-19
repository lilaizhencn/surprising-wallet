package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum MessageSendStatusEnum {
    /**
     * 登录来源 1web 2app
     */
    NO_SEND(0, "未发送"),
    SEND_OK(1, "发送成功"),
    VERIFY_OK(2, "验证成功"),
    SEND_FAIL(3, "发送失败"),
    EXPIRE(-1, "已过期"),
    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}

package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

@AllArgsConstructor
public enum TypeEnum {

    /**
     * 发送验证码类型 1手机 2邮箱
     */
    VERIFY_TYPE_EMAIL(1),
    VERIFY_TYPE_PHONE(2),

    /**
     * 提币类型 1谷歌 2手机 3邮箱
     */
    WITHDRAW_CODE_VERIFY_TYPE_GOOGLE(1),
    WITHDRAW_CODE_VERIFY_TYPE_PHONE(2),
    WITHDRAW_CODE_VERIFY_TYPE_EMAIL(3),

    /**
     * 用户行为类型 1. 充值 2. 提现 3. 充币 4. 提币 5. 买入委托 6. 卖出委托 7.站内互转
     */
    USER_ACTION_TYPE_DEPOSIT_LEGAL_CURRENCY(1),
    USER_ACTION_TYPE_WITHDRAW_LEGAL_CURRENCY(2),
    USER_ACTION_TYPE_DEPOSIT_DIGITAL_CURRENCY(3),
    USER_ACTION_TYPE_WITHDRAW_DIGITAL_CURRENCY(4),
    USER_ACTION_TYPE_BUY(5),
    USER_ACTION_TYPE_SELL(6),
    USER_ACTION_TYPE_INNER_TRNASFER(7),

    ;


    @Setter
    @Getter
    private int type;
}

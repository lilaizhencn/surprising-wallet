package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 状态枚举，定义系统中所有状态相关的常量值，包括用户性别、等级、认证状态、绑定状态以及币种状态等。
 *
 * <p>每个枚举常量的整数值通过 {@link #status} 字段获取。</p>
 *
 * <h3>枚举分类：</h3>
 * <ul>
 *   <li><b>用户性别</b>：{@link #USER_GENDER_MALE}、{@link #USER_GENDER_FEMALE}</li>
 *   <li><b>用户等级</b>：{@link #USER_GRADE_NO_AUTH}、{@link #USER_GRADE_GENERAL}、{@link #USER_GRADE_MEDIUM}、{@link #USER_GRADE_SENIOR}</li>
 *   <li><b>交易密码</b>：{@link #USER_TRADE_PASS_NOSET}、{@link #USER_TRADE_PASS_SETED}、{@link #USER_TRADE_PASS_PERIOD}</li>
 *   <li><b>邮箱绑定</b>：{@link #USER_MAIL_NOT_BIND}、{@link #USER_MAIL_BINDED}</li>
 *   <li><b>手机号绑定</b>：{@link #USER_PHONE_NOT_BIND}、{@link #USER_PHONE_BINDED}</li>
 *   <li><b>初级认证</b>：{@link #USER_PRIMARY_AUTH_REFUSE}、{@link #USER_PRIMARY_AUTH_NO_COMMIT}、{@link #USER_PRIMARY_AUTH_WAIT}、{@link #USER_PRIMARY_AUTH_PASSED}</li>
 *   <li><b>高级认证</b>：{@link #USER_SENIOR_AUTH_REFUSE}、{@link #USER_SENIOR_AUTH_NO_COMMIT}、{@link #USER_SENIOR_AUTH_WAIT}、{@link #USER_SENIOR_AUTH_PASSED}</li>
 *   <li><b>视频认证</b>：{@link #USER_VIDEO_AUTH_REFUSE}、{@link #USER_VIDEO_AUTH_NO_COMMIT}、{@link #USER_VIDEO_AUTH_WAIT}、{@link #USER_VIDEO_AUTH_PASSED}</li>
 *   <li><b>用户状态</b>：{@link #USER_STATUS_NORMAL}、{@link #USER_STATUS_DELETED}、{@link #USER_STATUS_FORBIDDEN}</li>
 *   <li><b>谷歌绑定</b>：{@link #USER_GOOGLE_AUTH_NO_BIND}、{@link #USER_GOOGLE_AUTH_BINDED}、{@link #USER_GOOGLE_AUTH_CLOSED}</li>
 *   <li><b>币种状态</b>：{@link #COIN_STATUS_ONLINE}、{@link #COIN_STATUS_OFFLINE}</li>
 * </ul>
 */
@AllArgsConstructor
public enum StatusEnums {

    /**
     * 用户性别 1.男  2.女
     */
    USER_GENDER_MALE(1),
    USER_GENDER_FEMALE(2),

    /**
     * 用户等级 0未认证 1.普通 2.中级 3.高级
     */

    USER_GRADE_NO_AUTH(0),
    USER_GRADE_GENERAL(1),
    USER_GRADE_MEDIUM(2),
    USER_GRADE_SENIOR(3),

    /**
     * 交易密码设置标示 0. 未设置 1. 已设置 -1. 过期
     */
    USER_TRADE_PASS_NOSET(0),
    USER_TRADE_PASS_SETED(1),
    USER_TRADE_PASS_PERIOD(-1),

    /**
     * 邮箱设置标示 0. 未设置 1. 已设置
     */
    USER_MAIL_NOT_BIND(0),
    USER_MAIL_BINDED(1),

    /**
     * 手机号设置标示 0. 未设置 1. 已设置
     */
    USER_PHONE_NOT_BIND(0),
    USER_PHONE_BINDED(1),

    /**
     * 初级认证状态  -1：认证被拒绝 0：未提交认证资料 1：待认证 2：认证通过
     */
    USER_PRIMARY_AUTH_REFUSE(-1),
    USER_PRIMARY_AUTH_NO_COMMIT(0),
    USER_PRIMARY_AUTH_WAIT(1),
    USER_PRIMARY_AUTH_PASSED(2),

    /**
     * 高级认证状态  -1：认证被拒绝 0：未提交认证资料 1：待认证 2：认证通过
     */
    USER_SENIOR_AUTH_REFUSE(-1),
    USER_SENIOR_AUTH_NO_COMMIT(0),
    USER_SENIOR_AUTH_WAIT(1),
    USER_SENIOR_AUTH_PASSED(2),

    /**
     * 视频认证状态  -1：认证被拒绝 0：未提交认证资料 1：待认证 2：认证通过
     */
    USER_VIDEO_AUTH_REFUSE(-1),
    USER_VIDEO_AUTH_NO_COMMIT(0),
    USER_VIDEO_AUTH_WAIT(1),
    USER_VIDEO_AUTH_PASSED(2),

    /**
     * 用户状态 1正常 -1删除 -2禁用
     */
    USER_STATUS_NORMAL(1),
    USER_STATUS_DELETED(-1),
    USER_STATUS_FORBIDDEN(-2),

    /**
     * 用户谷歌绑定状态 0.未绑定 1.绑定 2是取消绑定
     */
    USER_GOOGLE_AUTH_NO_BIND(0),
    USER_GOOGLE_AUTH_BINDED(1),
    USER_GOOGLE_AUTH_CLOSED(2),

    /**
     * 币种状态 1是上线 0是下线
     */
    COIN_STATUS_ONLINE(1),
    COIN_STATUS_OFFLINE(0),

    ;
    @Setter
    @Getter
    private int status;
}

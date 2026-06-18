package com.surprising.common;

import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */

public enum RespCode {

    //登录模块 使用 login前缀
    LOGIN_ERROR_10000(10000, "登录异常"),
    LOGIN_ERROR_10001(10001, "用户名错误"),
    LOGIN_ERROR_10002(10002, "密码错误"),
    LOGIN_ERROR_10003(10003, "账号被禁用"),

    //路由异常
    ROUTER_ERROR_20000(20000, "路由创建异常"),
    ROUTER_ERROR_20001(20001, "路由更新异常"),

    //数据字典
    DICTIONARY_ERROR_30000(30000, "数据字典更新异常"),
    DICTIONARY_ERROR_30001(30001, "数据字典创建异常"),
    DICTIONARY_ERROR_30002(30002, "数据字典项创建异常"),
    DICTIONARY_ERROR_30003(30003, "更新数据字典项TREE异常"),
    /**
     * 全局异常
     */
    COMMON_PARAM_BLANK(-1, "参数为空"),
    USER_TOKEN_ERROR(9999, "token验证失败"),
    SQL_SQLINJECT_ERROR(9998, "防sql注入捕获"),
    USER_PWDORUNAME_ERROR(401, "用户名或密码错误或者为空"),
    USER_AUTHCODE_EXPIRED(402, "验证码过期"),
    USER_AUTHCODE_ERROR(403, "验证码错误"),
    USER_FDPASSWORDAU_ERROR(456, "交易密码格式不正确"),
    USER_FDANDLOGINSAME_ERROR(477, "交易密码与登录密码相同"),
    //登录模块 使用 login前缀
    UNAME_OR_PASSWORD_ERROR(405, "用户名或密码错误"),
    USER_USEREXIST(406, "用户已存在"),
    USER_USERBANNED(407, "用户已禁用,请联系管理员"),
    USER_REGISTERERROR(408, "注册失败"),
    USER_FINDPWD_NOUSER(409, "用户不存在"),
    USER_RESRTPWD_FAIL(410, "重置密码失败"),
    USER_IMGCODE_ERROR(412, "图片验证码错误"),
    USER_BIND_EMAIL_FORMAT_ERROR(413, "邮箱格式错误"),
    USER_BIND_EMAIL_ERROR_EXIST(414, "邮箱已经被绑定"),
    UPLOAD_FILE_ERROR(415, "文件上传失败"),
    // 用户
    INTEGRAL_NOT_ENOUGH(416, "用户积分不足"),
    USER_AUTH_UPDATE_ERROR(416, "资料有误，请重新填写"),
    USER_AUTHCOUNT_ERROR(4160, "您已连续多次认证失败， 请十分钟后重试"),
    USER_BIND_EMAIL_ERROR(417, "邮箱绑定失败"),
    TOOLS_NOT_ENOUGH(418, "所选道具不足"),
    USER_QUESTION_OP_ERROR(422, "用户操作反馈问题失败"),
    USER_QUESTION_RECORD_NOT_EXIST(418, "用户提问记录不存在"),
    USER_QUESTION_AUTH_ERROR(419, "用户无权限删除此记录"),
    USER_QUESTION_SELECTAUTH_ERROR(4190, "用户无权限查看此记录"),
    USER_PWD_EMAIL_ERROR(420, "重置密码邮件发送失败"),
    USER_PHONE_EXIST(421, "手机号已经被使用"),
    USER_PHONE_INCORRECT(422, "输入的手机号码不正确"),
    USER_CODE_INCORRECT(423, "输入验证码不正确"),
    USER_AUTHCODE_FORMATERROR(423, "验证码格式有误"),
    USER_PWD_EMAIL_ISOTHERS(424, "不是本人邮箱,请确认!"),
    USER_ADDRESS_SET_FAIL(43000, "用户已经设置过"),
    USER_NOT_LOGIN(430, "用户未登录"),
    USER_PASSWORD_CHECKED(443, "请输入6-18位密码"),
    USER_AUTH_HASCOMMIT(444, "用户已经提交过"),
    USER_PHONE_ERROR(511, "手机格式错误"),
    USER_NOTEXIST(512, "用户不存在"),
    USER_UNAME_NOTMATCH(514, "用户名格式不正确"),
    USER_UNAME_ISSAME(515, "新用户名与原用户名相同"),
    USER_THIRDPARTY_ERROR(516, "第三方用户信息出错"),
    USER_INSUFFICIENT_BALANCE(517, "余额不足"),
    // 活动
    ACTIVITY_END(580, "活动结束"),
    //找回密码相关
    USER_OLDPWD_ERROR(555, "用户原密码错误"),
    //短信相关
    SMS_SEND_ERROR(501, "短信发送失败"),
    SMS_SEND_NOTEXPRIED(502, "发送短信操作频繁,请稍后再试"),
    SYSTEM_ERROR(10000, "系统错误"),
    REDIS_CON_ERROR(20000, "Redis连接失败"),
    QSK_DETAIL_LENGTHERROR(600, "反馈内容过长"),
    USER_NOT_AUTH(41700, "用户未认证"),
    USER_AUTH_REFUSED(41701, "认证被拒绝"),
    USER_AUTH_REVIEW(41702, "认证审核中"),
    USER_AUTH_CHECK_OK(41703, "审核通过"),
    USER_HAS_SIGNED(42000, "亲,今日已经签过到了"),
    USER_SIGN_ERROR(42001, "签到失败,请稍后重试"),
    USER_LOGIN_ERROR(455, "您已连续多次输入错误密码， 请五分钟后重试"),
    USER_RENAME_FAIL(466, "更改用户名失败"),
    USER_FDPASSWORD_ERROR(467, "绑定交易密码失败"),
    USER_FDPASSWORD_NOTMATCH(468, "交易密码错误"),
    SMS_SEND_OK(567, "短信发送成功"),
    MAIL_SEND_OK(568, "邮件发送成功"),
    MAIL_SEND_ERROR(569, "邮件发送失败"),

    LOGIN_RISK_LIST(570, "无登录限制用户"),
    SENIOR_RISK_LIST(571, "无验证限制用户"),
    POSTCODE_ERROR(699, "邮编格式有误"),
    INVITATION_CODE(700, "邀请码不存在"),
    ID_CARD_IMAGE_UPLOAD_ERROR(701, "请重新上传认证身份照片"),
    SECOND_PWD_WRONG(10001, "交易密码错误"),
    USER_WRONG(10002, "账户不存在"),
    AMOUNT_WRONG(10003, "数量错误"),
    AMOUNT_NOT_ENOUGH(10004, "余额不足"),
    ADDRESS_WRONG(10005, "该地址有误"),
    PAYMENTID_WRONG(10006, "paymentid不合法"),
    USER_POWER(10007, "该用户无此权限"),
    CURRENCY_TAKECOIN_WRONG(10008, "提现已暂停"),
    CURRENCY_WRONG(10009, "该币种不能兑换"),
    MEMO_WRONG(10010, "memo length range 6-10!"),
    NEED_BIND_EMAIL(10011, "提现之前需要绑定邮箱"),
    WITHDRAW_NO_FOUND_OR_HAS_TOKE(10012, "提币记录不存在或已提币"),
    CHECK_WITHDRAW_STATUS(10013, "确认异常,请检查提笔单状态"),
    IP_NOT_BLACK_LIST(10014, "访问IP不在白名单"),
    ROBOT_API_CLOSE_ORDER(10015, "API未开启"),
    RECHARGE_MANUAL_HAS_CHECK(10016, "手动充值单已经审核过"),
    USER_AUTH(10001, "该账户无此权限"),
    PASSWORD_WRONG(10002, "密码错误"),
    ;


    @Setter
    @Getter
    private int errorCode;
    @Setter
    @Getter
    private String errorMsg;

    RespCode(int i, String desc) {
        errorCode = i;
        errorMsg = desc;
    }
}

package com.surprising.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author zhangjianghong
 * @date 2017/5/9
 */
@AllArgsConstructor
@NoArgsConstructor
public enum RedisPrefix {

    DBSMS(1, "SMS", 5 * 60),
    DBToken(2, "tken", 30 * 60),
    DBUser(3, "user", 31 * 60),
    DBIMG(4, "IMG", 30 * 60),
    DBMAIL(5, "MAIL", 5 * 60),
    DBMAILPWD(6, "MLPWD", 5 * 60),
    DBQINIUTOKEN(7, "QINIU_TOKEN", 50 * 60),
    DBREARDID(8, "REWARD_ID", 1000),
    LOGINTIMES(9, "LOGINTIMES", 5 * 60),
    AUTHTIMES(10, "AUTHTIMES", 10 * 60),
    SMSwithdraw(11, "SMS_withdraw", 5 * 60),
    APPLOGIN(12, "TKENAPP", 7),
    DBUSERAPP(13, "USERAPP", 7),
    //防钓鱼码
    DBFISHINGCODE(14, "FISHINGCODE", 7),
    LOGIN_SECOND_VERIFY(15, "login_second_verify_type_", 7),
    LOGIN_SECOND_VERIFY_TYPE(16, "login_second_verify_type_", 7),
    ;
    @Setter
    @Getter
    private int dbLocation;
    @Setter
    @Getter
    private String prefix;
    @Setter
    @Getter
    private Integer expriedTime;
}

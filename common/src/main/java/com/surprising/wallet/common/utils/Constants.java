package com.surprising.wallet.common.utils;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
public final class Constants {
    private Constants() {
    }
    /**
     * 把充值交易推送到 `WALLET_DEPOSIT_KEY` 队列，让各自的业务线读取
     */
    public final static String WALLET_DEPOSIT_KEY = "sw:wallet:deposit:biz:";
    /**
     * 读取等待提现
     */
    public final static String WALLET_WITHDRAW_WAIT_KEY = "sw:wallet:withdraw:wait";
    public final static String WALLET_WITHDRAW_FAIL_KEY = "sw:wallet:withdraw:fail";
    public final static String WALLET_WITHDRAW_FAIL_KEY_TMP = "sw:wallet:withdraw:fail:tmp";
    /**
     * 等待第一次签名
     */
    public final static String WALLET_WITHDRAW_SIG_FIRST_KEY = "sw:wallet:withdraw:sig:first";
    /**
     * 存储第一次签名的中间值
     */
    public final static String WALLET_WITHDRAW_SIG_FIRST_TMP_KEY = "sw:wallet:withdraw:sig:first:tmp";
    /**
     * 等待第二次签名
     */
    public final static String WALLET_WITHDRAW_SIG_SECOND_KEY = "sw:wallet:withdraw:sig:second";
    /**
     * 存储第二次签名的中间值
     */
    public final static String WALLET_WITHDRAW_SIG_SECOND_TMP_KEY = "sw:wallet:withdraw:sig:second:tmp";
    /** 费率 (sat/vB)，由 FeeRateUpdater 自动从 mempool API 写入 */
    public final static String WALLET_FEE = "sw:wallet:withdraw:fee:currency:";
    /**
     * 签名完成，等待发送
     */
    public final static String WALLET_WITHDRAW_SIG_DONE_KEY = "sw:wallet:withdraw:sig:done";

    /**
     * 签名完成之后，发到各自的业务线
     */
    public final static String WALLET_WITHDRAW_TX_BIZ_KEY = "sw:wallet:withdraw:tx:biz:";
    public static final String UNSPENT_TX_ID = "unspent";
    /**
     * status 状态
     */
    //等待提现
    public static final short WAITING = 0;

    //签名中
    public static final short SIGNING = 1;
    //已发送
    public static final short SENT = 2;
    //已确认
    public static final short CONFIRM = 3;
    //已删除
    public static final short DELETE = -1;


    public static final String WITHDRAW = "withdraw";

}











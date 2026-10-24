package com.surprising.wallet.common.utils;

public final class Constants {
    private Constants() {
    }
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











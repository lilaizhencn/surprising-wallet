package com.surprising.wallet.common.utils;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class Constants {
    /**
     * 构造 {@code Constants}，初始化该组件运行所需的状态和依赖。
     */
    private Constants() {
    }
    /**
     * 定义 {@code UNSPENT_TX_ID} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
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


    /**
     * 定义 {@code WITHDRAW} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String WITHDRAW = "withdraw";

}











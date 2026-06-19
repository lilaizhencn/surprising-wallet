package com.surprising.common.config;

/**
 * @Desc: 事件常量
 * scott
 */
public class EventConstants {

    /**
     * 事件 注册用户信息
     */
    public final static String EVENT_REGISTER = "register";
    /**
     * 事件 订阅币对
     */
    public final static String EVENT_SUBSCRIBE = "subscribe";
    /**
     * 事件 获取K线数据
     */
    public final static String EVENT_TRADINGVIEW = "tradingView";
    /**
     * 事件 行情列表
     */
    public final static String EVENT_QUOTELIST = "list";
    /**
     * 事件 个人资产
     */
    public final static String EVENT_USERACCOUNT = "userAccount";
    /**
     * 事件 交易历史
     */
    public final static String EVENT_TRADEHISTORY = "tradeHistory";
    /**
     * 事件 委托队列
     */
    public final static String EVENT_ENTRUST = "entrust";
    /**
     * 事件 行情推送
     */
    public final static String EVENT_QUOTENOTIFY = "quoteNotify";
    /**
     * 事件 获取用户委托单
     */
    public final static String EVENT_USERORDER_GET = "getUserOrder";
    /**
     * 事件 推送用户委托信息
     */
    public static final String EVENT_USERORDER_PUSH = "userOrder";
    /**
     * 事件 获取用户成交单
     */
    public final static String EVENT_USERTRADE_GET = "getUserTrade";
    /**
     * 事件 推送用户成交信息
     */
    public static final String EVENT_USERTRADE_PUSH = "userTrade";
    /**
     * 事件 获取服务器时间
     */
    public final static String EVENT_SERVERTIME = "serverTime";
    /**
     * API事件 获取服务器时间
     */
    public final static String API_EVENT_REGISTER = "apiregister";
    /**
     * API事件 获取服务器时间
     */
    public final static String API_EVENT_TRADEHISTORY = "apiTradeHistory";
    /**
     * API事件 获取服务器时间
     */
    public final static String API_EVENT_ENTRUST = "apiEntrust";

    /**
     * 事件类型 获取
     */
    public final static int EVENT_TYPE_GET = 1;
    /**
     * 事件类型 推送
     */
    public final static int EVENT_TYPE_PUT = 2;


    /**  HOST  **/
    public final static String HOST = "host";
    public final static String PORT = "port";
    public final static String USER = "user";
    public final static String PASSWORD = "password";
    public final static String CURRENCYID = "currencyId";
    public final static String ADDRESS = "address";
    public final static String UUID = "uuid";
    public final static String SECRET = "secret";
    public final static String MSGCODE = "msgCode";
    public final static String URL_FORMAT = "http://%s:%s/";
}

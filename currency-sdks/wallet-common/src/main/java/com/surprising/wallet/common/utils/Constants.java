package com.surprising.wallet.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
//@Slf4j
@Component
public class Constants {
    /**
     * 把充值交易推送到 `WALLET_DEPOSIT_KEY` 队列，让各自的业务线读取
     */
    public final static String WALLET_DEPOSIT_KEY = "newex-wallet:deposit:biz:";
    public final static String WALLET_DEPOSIT_LOCK_KEY = "newex-wallet:deposit:biz:lock";
    /**
     * 读取等待提现
     */
    public final static String WALLET_WITHDRAW_WAIT_KEY = "newex-wallet:withdraw:wait";
    public final static String WALLET_WITHDRAW_FAIL_KEY = "newex-wallet:withdraw:fail";
    public final static String WALLET_WITHDRAW_FAIL_KEY_TMP = "newex-wallet:withdraw:fail:tmp";
    /**
     * 等待第一次签名
     */
    public final static String WALLET_WITHDRAW_SIG_FIRST_KEY = "newex-wallet:withdraw:sig:first";
    /**
     * 存储第一次签名的中间值
     */
    public final static String WALLET_WITHDRAW_SIG_FIRST_TMP_KEY = "newex-wallet:withdraw:sig:first:tmp";
    /**
     * 等待第二次签名
     */
    public final static String WALLET_WITHDRAW_SIG_SECOND_KEY = "newex-wallet:withdraw:sig:second";
    /**
     * 存储第二次签名的中间值
     */
    public final static String WALLET_WITHDRAW_SIG_SECOND_TMP_KEY = "newex-wallet:withdraw:sig:second:tmp";
    /**
     * perKb fee
     */
    public final static String WALLET_FEE = "newex-wallet:withdraw:fee:currency:";
    /**
     * 签名完成，等待发送
     */
    public final static String WALLET_WITHDRAW_SIG_DONE_KEY = "newex-wallet:withdraw:sig:done";

    /**
     * 扫描指定的区块
     */
    public final static String WALLET_SCAN_SPECIFIED_HEIGHT_KEY = "newex-wallet:scan:specified:height";
    /**
     * 解决用户充错币种的问题
     */
    public final static String WALLET_TRANSFER_BETWEEN_CURRENCY_KEY = "newex-wallet:transfer:between:currency";
    /**
     * 签名完成之后，发到各自的业务线
     */
    public final static String WALLET_WITHDRAW_TX_BIZ_KEY = "newex-wallet:withdraw:tx:biz:";
    public final static String WALLET_WITHDRAW_TX_LOCK_KEY = "newex-wallet:withdraw:tx:biz:lock";

    /**
     * iota发出交易之后可能需要重新发送
     */
    public final static String WALLET_IOTA_UNCONFIRMED_TX_KEY = "newex-wallet:iota:unconfirmed:tx";
    public static final String OMNI_HEADER = "6f6d6e69";
    public static final String UNSPENT_TX_ID = "unspent";
    public static NetworkParameters NET_PARAMS;
    /**
     * status 状态
     */
    //等待提现
    public static short WAITING = 0;

    //签名中
    public static short SIGNING = 1;
    //已发送
    public static short SENT = 2;
    //已确认
    public static short CONFIRM = 3;
    //已删除
    public static short DELETE = -1;


    //提现的两种类型，account类型的币会用到
    public static final String TRANSFER = "transfer";
    public static final String WITHDRAW = "withdraw";


    //生成内部地址的用户ID
    public static Long USER_ID = 0L;
    public static Integer BIZ = 0;
    @Value("${atomex.wallet.network:main}")
    public String NETWORK;

    @PostConstruct
    public void init() {
//        log.info("当前环境={}", NETWORK);
        if (NETWORK.equals("test")) {
            Constants.NET_PARAMS = TestNet3Params.get();
        } else {
            Constants.NET_PARAMS = MainNetParams.get();
        }
    }


}















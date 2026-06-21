package com.surprising.common.config;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Legacy constants retained for old jobs and SAFE/token compatibility.
 *
 * <p>Do not allocate new-chain runtime ids here and do not treat these constants as the
 * source of truth. New chains use {@code chain_profile.runtime_currency_id}. In particular,
 * this class's historical {@code LTC = 2} conflicts with modern wallet ids and must not be
 * used by the LTC/DOGE/BCH wallet flow.</p>
 *
 * @author lilaizhencn
 */
public class CurrencyIds {
    /**
     *
     */
    public static final Map<Integer, String> EOS_CONTRACT_MAP = Maps.newConcurrentMap();
    /**
     * 币种id 比特币btc
     */
    public static final int BTC = 1;
    /**
     * 莱特币lite
     */
    public static final int LTC = 2;
    /**
     * //以太坊eth
     */
    public static final int ETH = 3;
    /**
     * //BCH
     */
    public static final int BCH = 5;
    /**
     * //ETC
     */
    public static final int ETC = 6;
    /**
     * //SAFE
     */
    public static final int SAFE = 7;
    /**
     * //DASH
     */
    public static final int DASH = 8;
    /**
     * //DFT 平台币 SAFE资产DF平台币
     */
    public static final int DF = 9;
    /**
     * //BCH
     */
    public static final int BCHABC = 10;
    /**
     * //CORAL SAFE资产珊瑚链
     */
    public static final int CRL = 12;
    /**
     * //FTO futurocoin
     */
    public static final int FTO = 13;
    /**
     * //PXT 凤凰令 SAFE资产凤凰令
     */
    public static final int PXT = 14;
    /**
     * //GameSky
     */
    public static final int GSKY = 15;
    /**
     * //EOS
     */
    public static final int EOS = 16;
    /**
     * //GFC 垃圾币
     */
    public static final int GFC = 17;
    /**
     * //BSTZ 宝石通证 SAFE资产宝石通证
     */
    public static final int BSTZ = 19;
    /**
     * //VDS币
     */
    public static final int VDS = 20;
    /**
     * //AIC币
     */
    public static final int AIC = 22;
    /**
     * //本体
     */
    public static final int ONT = 23;
    /**
     * //GOD 比特币分叉币
     */
    public static final int GOD = 26;
    /**
     * //TRON 波场
     */
    public static final int TRX = 27;
    /**
     * //BCC 比特币分叉币
     */
    public static final int BCC = 28;
    /**
     * //BNB 币安币
     */
    public static final int BNB = 30;
    /**
     * //MOC 摩云币
     */
    public static final int MOC = 31;
    /**
     * //GBL gbl平台币
     */
    public static final int GBL = 100;
    /**
     * //ONG
     */
    public static final int ONG = 999;
    /**
     * 波场 TRON TRC20代币  //BTT
     */
    public static final int TRX_BTT = 37;
    /**
     * ERC20代币
     * <p>
     * 新增代币修改5行代码:
     * 1.币种id 2.币种合约 3.币种map 4.合约map 5.精度map
     */
    public static final int ERC20_ALL = 200;
    /**
     * //BGC -- ERC20
     */
    public static final int ERC20_BGC = 21;
    /**
     * //AEBT -- ERC20
     */
    public static final int ERC20_AEBT = 24;
    /**
     * //GT gate
     */
    public static final int ERC20_GT = 25;
    /**
     * //HT
     */
    public static final int ERC20_HT = 31;
    /**
     * //OKB
     */
    public static final int ERC20_OKB = 32;
    /**
     * //LEO
     */
    public static final int ERC20_LEO = 33;
    /**
     * //B95
     */
    public static final int ERC20_B95 = 34;
    /**
     * //B95
     */
    public static final int ERC20_CA = 35;
    /**
     * //B95
     */
    public static final int ERC20_HIC = 36;
    /**
     * //B95
     */
    public static final int ERC20_K3 = 38;
    /**
     * //B95
     */
    public static final int ERC20_WNET = 40;

    /**
     * safe合约币
     */
    public static final Integer[] SAFE_CONTRACT = {DF, CRL, PXT, BSTZ};
    /**
     * eos合约币
     */
    public static final Integer[] EOS_CONTRACT = {GSKY, EOS};

    /**
     * TRC20 token id
     */
    public static final String TRC20_TOKEN_BTT = "1002000";
    /**
     * TRC20 tokenId Map
     */
    public static final Map<Integer, String> TRC20_TOKEN_MAP = Maps.newConcurrentMap();
    /**
     * TRC20 decimal
     */
    public static final Map<Integer, BigDecimal> TRC20_DECIMAL = Maps.newConcurrentMap();

    public static final BigDecimal DECIMAL_18 = new BigDecimal("1000000000000000000");
    public static final BigDecimal DECIMAL_8 = new BigDecimal("100000000");
    public static final BigDecimal DECIMAL_6 = new BigDecimal("1000000");
    public static final BigDecimal DECIMAL_2 = new BigDecimal("100");

    public static final List<Integer> MSGCODE_List = Lists.newArrayList();

    /**
     * //ERC20配置
     */
    public static final int ERC20 = 18;

    /**
     * 钱包类型//充币
     */
    public static final int walletTakeAdd = 1;
    /**
     * //提币
     */
    public static final int walletTypeTake = 2;
    /**
     * //冷钱包
     */
    public static final int walletTypeCold = 3;

    /**
     * 用户币地址类型
     */
    public static final int userAddressRecharge = 1;//充币
    public static final int userAddressWithdraw = 2;//提币

    /**
     * 用户行为actionId，对应user_action
     */
    public static final int recharge = 1;//充值
    public static final int withdraw = 2;//提现
    public static final int rechargeCoin = 3;//充币
    public static final int withdrawCoin = 4;//提币
    public static final int buy = 5;//买入
    public static final int sell = 6;//卖出


    /**
     * 充提，资产写死8位
     */
    public static final int pointAbroad = 8;//充提，资产写死8位

    /**
     * 充币状态
     */
    public static final int rechargePending = 1;//充值中
    public static final int rechargeSuccess = 2;//充币成功

    /**
     * 提币状态
     */
    public static final int withdrawStatus1 = 1;//未审核
    public static final int withdrawSuccess = 2;//提币成功（已完成）
    public static final int withdrawFailure = 5;//风控审核失败
    public static final int withdrawStatus6 = 6;//提币中（未转币）
    public static final int withdrawStatus7 = 7;//提币中（已转币）

    /**
     * DF社区配置
     */
    public static final int totalSalesAmount = 700000000;//DF发售总量7亿
    public static final int createTeamLockPositionAmount = 500000;//创建战队成为队长锁仓DF500000
    public static final int basicLockPositionAmount = 100;//参加战队赛、擂台赛锁仓DF100
    public static final int enrolmentWarTeamDestroyAmount = 10;//参加战队赛报名销毁DF10
    public static final int enrolmentArenaMatchDestroyAmount = 100;//参加战队赛报名销毁DF100
    public static final int upperLimitPerDayAmount = 513000;//DF每天发放（默认）上限

    /**
     * 拉新奖励
     */
    public static final int pullNewRegisterAward = 20;//注册即奖励20GBL
    public static final int pullNewRecommendAward = 20;//推荐注册奖励20GBL
    public static final int pullNewTradeAward = 2;//推荐用户首次交易，推荐人向上15代奖励2DF

    /**
     * DF 交易区
     */
    public static final int dfReleaseNodeLimit = 200000000;//释放节点上限 2亿

    /**
     * 波场币
     * <p>
     * TRON --- star ---
     */

    //TRX SUN  1 TRX = 1,000,000 SUN
    public static final int TRX_SUN = 1000000;


    static {
        EOS_CONTRACT_MAP.put(GSKY, "gameskytoken");
        EOS_CONTRACT_MAP.put(EOS, "eosio.token");
        //token map
        TRC20_TOKEN_MAP.put(TRX_BTT, TRC20_TOKEN_BTT);
        //精度
        TRC20_DECIMAL.put(TRX, DECIMAL_6);
        TRC20_DECIMAL.put(TRX_BTT, DECIMAL_6);
    }
}

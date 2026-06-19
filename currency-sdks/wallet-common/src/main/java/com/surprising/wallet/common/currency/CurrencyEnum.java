package com.surprising.wallet.common.currency;

import com.google.common.collect.Sets;
import com.surprising.wallet.common.exception.UnsupportedCurrency;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Set;

import static com.surprising.wallet.common.currency.CurrencyEnum.Decimal.*;

/**
 * @author lilaizhen
 * @data 27/03/2018
 */

public enum CurrencyEnum {

    BTC(1, "btc", 7, 1, 6, EIGHT),
    ETH(2, "eth", 121, 12, 120, EIGHTEEN),
    LTC(3, "ltc", 7, 1, 6, EIGHT),
    ETC(4, "etc", 121, 12, 120, EIGHTEEN),
    BCH(5, "bch", 7, 1, 6, EIGHT),
    ERC20(7, "erc20", 121, 12, 120, EIGHTEEN),
    MTX(8, "mtx", 121, 12, 120, EIGHTEEN, "0x0af44e2784637218dd1d32a322d44e603a8f0c6a"),
    CPG(9, "cpg", 121, 12, 120, EIGHTEEN, "0x6620749a9bd0ba09e9921ad77f040d297a861b49"),
    OMG(10, "omg", 121, 12, 120, EIGHTEEN, "0xd26114cd6EE289AccF82350c8d8487fedB8A0C07"),
    CHP(11, "chp", 121, 12, 120, EIGHTEEN, "0xf3db7560E820834658B590C96234c333Cd3D5E5e"),
    CEL(12, "cel", 121, 12, 120, FOUR, "0xaaAEBE6Fe48E54f431b0C390CfaF0b017d09D42d"),
    LYM(13, "lym", 121, 12, 120, EIGHTEEN, "0x57aD67aCf9bF015E4820Fbd66EA1A21BED8852eC"),
    ACT(14, "act", 61, 12, 60, FIVE),
    KCASH(15, "kcash", 61, 12, 60, FIVE, "COND41iays8576giHf6M6Yox1DiBrDmgVyzJ"),
    SSC(16, "ssc", 61, 12, 60, FIVE),
    LET(17, "let", 61, 12, 60, FIVE, "CONDuPQkPuKqD5NM2XwHJCjnZiiTs2GAJDLB"),
    CTB(18, "ctb", 121, 12, 120, EIGHTEEN, "0x43e7e2e3a893a7eaa5219ffc087c7497c350c8c2"),

    BMB(19, "bmb", 121, 12, 120, EIGHT, "0x9c1f0de4fec6ae8efb65f2e45a1c1b625e35e7bf"),
    XRP(20, "xrp", 11, 5, 10, SIX),
    IOTA(21, "iota", 11, 5, 10, SIX),
    BTM(22, "btm", 12, 2, 10, EIGHT, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
    TRX(23, "trx", 121, 12, 120, SIX),
    LUCKYWIN(24, "luckywin", 121, 12, 120, EIGHTEEN),
    CT(25, "ct", 121, 12, 120, EIGHT),
    //点卡
    CMDK(26, "cmdk", 121, 12, 120, EIGHT),
    DDD(27, "ddd", 121, 12, 120, EIGHTEEN, "0x9f5f3cfd7a32700c93f971637407ff17b91c7342"),
    MVP(28, "mvp", 121, 12, 120, EIGHTEEN, "0x8a77e40936bbc27e80e9a3f526368c967869c86d"),
    BAIC(29, "baic", 121, 12, 120, EIGHTEEN, "0x3258FF5a650d2A4601c12bc3DA495557EF354066"),
    WIN(30, "win", 121, 12, 120, EIGHTEEN, "0xbfaa8cf522136c6fafc1d53fe4b85b4603c765b8"),
    EOS(31, "eos", 1, 2, 1, FOUR, "eos-eosio.token-EOS"),
    ADA(32, "ada", 121, 12, 120, EIGHTEEN),
    GET(33, "get", 121, 12, 120, EIGHTEEN, "0x60c68a87be1e8a84144b543aacfa77199cd3d024"),
    OF(34, "of", 121, 12, 120, THREE),
    NEO(35, "neo", 4, 3, 3, ZERO, "0xc56f33fc6ecfcd0c225c4ab356fee59390af8560be0e930faebe74a6daff7c9b"),
    GAS(36, "gas", 4, 3, 3, EIGHT, "0x602c79718b16e442de58778e148d0b1084e3b2dffd5de6b7b16cee7969282de7"),
    MDS(38, "mds", 121, 12, 120, EIGHTEEN, "0x66186008C1050627F979d464eABb258860563dbE"),
    SDS(37, "sds", 4, 3, 3, EIGHT, "0x6fad54d8cc692fc808fd97a207836a846c217705"),
    GNX(39, "gnx", 121, 12, 120, NINE, "0x6ec8a24cabdc339a06a172f8223ea557055adaa5"),
    RRC(40, "rrc", 121, 12, 120, TWO, "0xB6259685685235c1eF4B8529e7105f00BD42b9f8"),
    DPY(41, "dpy", 121, 12, 120, EIGHTEEN, "0x6c2adc2073994fb2ccc5032cc2906fa221e9b391"),
    ONT(42, "ont", 4, 3, 3, ZERO, "0100000000000000000000000000000000000000"),
    ONG(43, "ong", 4, 3, 3, NINE, "0200000000000000000000000000000000000000"),
    FTI(44, "fti", 121, 12, 120, EIGHTEEN, "0x943ed852dadb5c3938ecdc6883718df8142de4c8"),
    YOU(45, "you", 121, 12, 120, EIGHTEEN, "0x34364bee11607b1963d66bca665fde93fca666a8"),
    HUR(46, "hur", 121, 12, 120, EIGHTEEN, "0xcdb7ecfd3403eef3882c65b761ef9b5054890a47"),
    NEP5(47, "nep5", 101, 10, 100, EIGHTEEN),
    ORC_TOKEN(48, "orcToken", 121, 12, 120, EIGHTEEN),

    PAG(49, "pag", 121, 12, 120, FOUR, "0x000028009c219f9abe02d3fbf4edc43def7e7ddb7224257384"),

    FBS001(50, "FBS001", 100, 100, 100, ZERO),
    CMX06(51, "cmx06", 100, 100, 100, ZERO),

    PST(52, "pst", 121, 12, 120, EIGHTEEN, "0x5d4abc77b8405ad177d8ac6682d584ecbfd46cec"),
    BKBT(53, "bkbt", 121, 12, 120, EIGHTEEN, "0x6a27348483d59150ae76ef4c0f3622a78b0ca698"),
    PVB(54, "pvb", 121, 12, 120, EIGHTEEN, "0xcb324e4c8c1561d547c38bd1d4a3b12a405b8019"),
    LRT(55, "lrt", 121, 12, 120, EIGHT, "0xe0f0abce99ba75e0a369514cf4359cc698824efc"),
    EOSC(56, "eosc", 61, 30, 60, FOUR, "eosc-eosio-EOS"),
    ZXT(57, "zxt", 121, 12, 120, EIGHTEEN, "0x8ed5afcb8877624802a0cbfb942c95c2b7c87146"),
    ADD(58, "add", 61, 30, 60, FOUR, "eos-eosadddddddd-ADD"),
    MEETONE(59, "meetone", 61, 30, 60, FOUR, "eos-eosiomeetone-MEETONE"),
    EETH(60, "eeth", 61, 30, 60, FOUR, "eos-ethsidechain-EETH"),
    PANDA(61, "panda", 121, 12, 120, EIGHTEEN, "0x0a5dc2204dfc6082ef3bbcfc3a468f16318c4168"),
    GUSD(62, "gusd", 121, 12, 120, TWO, "0x056fd409e1d7a124bd7017459dfea2f387b6d5cd"),
    EOSDAC(63, "eosdac", 61, 30, 60, FOUR, "eos-eosdactokens-EOSDAC"),
    XLM(64, "xlm", 31, 15, 30, SEVEN),
    ZRX(65, "zrx", 121, 12, 120, EIGHTEEN, "0xe41d2489571d322189246dafa5ebde1f4699f498"),
    MKR(66, "mkr", 121, 12, 120, EIGHTEEN, "0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2"),
    AE(67, "ae", 121, 12, 120, EIGHTEEN, "0x5ca9a71b1d01849c0a95490cc00559717fcf0d1d"),
    BAT(68, "bat", 121, 12, 120, EIGHTEEN, "0x0d8775f648430679a709e98d2b0cb6250d2887ef"),
    REP(69, "rep", 121, 12, 120, EIGHTEEN, "0x1985365e9f78359a9B6AD760e32412f4a445E862"),
    WTC(70, "wtc", 121, 12, 120, EIGHTEEN, "0xb7cb1c96db6b22b0d3d9536e0108d062bd488f74"),
    GNT(71, "gnt", 121, 12, 120, EIGHTEEN, "0xa74476443119A942dE498590Fe1f2454d7D4aC0d"),
    PPT(72, "ppt", 121, 12, 120, EIGHT, "0xd4fa1460f537bb9085d22c7bccb5dd450ef28e3a"),
    SNT(73, "snt", 121, 12, 120, EIGHTEEN, "0x744d70fdbe2ba4cf95131626614a1763df805b9e"),
    DOGE(74, "doge", 31, 6, 30, EIGHT),
    QTB(75, "qtb", 121, 12, 120, EIGHTEEN, "0x0317ada015cf35244b9f9c7d1f8f05c3651833ff"),
    FC(76, "fc", 121, 12, 120, EIGHT, "0x659c6a81AFa57A75a3E512587E920b97304D330a"),
    TUSD(77, "tusd", 121, 12, 120, EIGHTEEN, "0x8dd5fbce2f6a956c3022ba3663759011dd51e73e"),
    PAX(78, "pax", 121, 12, 120, EIGHTEEN, "0x8e870d67f660d95d5be530380d0ec0bd388289e1"),
    USDC(79, "usdc", 121, 12, 120, SIX, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
    DASH(80, "dash", 61, 20, 60, EIGHT),
    XEM(81, "xem", 11, 10, 10, SIX),
    QBT(82, "qbt", 121, 12, 120, EIGHTEEN, "0x4b4fB2F2DC64a2cf2AFBc8119ca2Eabbd99a575b"),
    KBCC(83, "kbcc", 121, 12, 120, SIX, "0x6ac544849414fbafdc0adba7c77bbd73a7d36ac1"),
    ELET(84, "elet", 121, 12, 120, EIGHT, "0x0568025c55c21bda4bc488f3107ebfc8b3d3ef2d"),
    LRC(85, "lrc", 121, 12, 120, EIGHTEEN, "0xEF68e7C694F40c8202821eDF525dE3782458639f"),
    OLT(86, "olt", 121, 12, 120, EIGHTEEN, "0x64a60493d888728cf42616e034a0dfeae38efcf0"),
    LRN(87, "lrn", 4, 3, 3, EIGHT, "0x06fa8be9b6609d963e8fc63977b9f8dc5f10895f"),
    GARD(89, "gard", 121, 12, 120, EIGHTEEN, "0x5c64031c62061865e5fd0f53d3cdaef80f72e99d"),
    RBTC(90, "rbtc", 121, 12, 120, EIGHTEEN),
    ZEC(91, "zec", 13, 2, 12, EIGHT),
    RIF(92, "rif", 121, 12, 120, EIGHTEEN, "0x2acc95758f8b5f583470ba265eb685a8f45fc9d5"),
    RSK_TOKEN(93, "rskToken", 121, 12, 120, EIGHTEEN),
    CNY(10000, "cny", 121, 12, 120, EIGHT);



    public static Set<CurrencyEnum> ERC20_SET = Sets.immutableEnumSet(
            OMG, LYM, GUSD, ZRX, MKR, BAT, SNT, PAX, USDC);

    public static Set<CurrencyEnum> ATP_SET = Sets.immutableEnumSet(KCASH, LET);

    public static Set<CurrencyEnum> ONT_ASSET = Sets.immutableEnumSet(ONT,ONG);

    public static Set<CurrencyEnum> ORC_TOKEN_SET = Sets.immutableEnumSet(PAG);
    public static Set<CurrencyEnum> NEP5_SET = Sets.immutableEnumSet(SDS, LRN);
    public static Set<CurrencyEnum> NEO_ASSET = Sets.immutableEnumSet(GAS, NEO);
    public static Set<CurrencyEnum> EOS_ASSET = Sets.immutableEnumSet(ADD, MEETONE, EETH, EOSDAC);
    public static Set<CurrencyEnum> RSK_TOKEN_ASSET = Sets.immutableEnumSet(RIF);

    private final int index;
    private final String name;
    private final String remark;
    private final long confirmNum;
    private final long depositConfirmNum;
    private final long withdrawConfirmNum;
    private final BigDecimal decimal;

    /**
     * @param index
     * @param name
     * @param confirmNum         : 更新到多少个确认数
     * @param depositConfirmNum  ： 充值确认数
     * @param withdrawConfirmNum ： 提现确认数
     */
    CurrencyEnum(final int index, final String name, final int confirmNum, final int depositConfirmNum,
                 final int withdrawConfirmNum, final Decimal decimal) {
        this(index, name, confirmNum, depositConfirmNum, withdrawConfirmNum, decimal, "");
    }

    CurrencyEnum(final int index, final String name, final int confirmNum, final int depositConfirmNum,
                 final int withdrawConfirmNum, final Decimal decimal, final String remark) {
        this.index = index;
        this.name = name;
        this.confirmNum = confirmNum;
        this.depositConfirmNum = depositConfirmNum;
        this.withdrawConfirmNum = withdrawConfirmNum;
        this.decimal = BigDecimal.valueOf(decimal.getDecimal());
        this.remark = remark;
    }

    public static CurrencyEnum parseValue(final int index) {
        for (final CurrencyEnum currencyEnum : CurrencyEnum.values()) {
            if (currencyEnum.getIndex() == index) {
                return currencyEnum;
            }
        }
        throw new UnsupportedCurrency(String.valueOf(index));
    }

    public static CurrencyEnum parseName(final String name) {
        for (final CurrencyEnum currencyEnum : CurrencyEnum.values()) {
            if (currencyEnum.getName().equalsIgnoreCase(name.toLowerCase())) {
                return currencyEnum;
            }
        }
        throw new UnsupportedCurrency(name);
    }

    public static CurrencyEnum parseContract(final String contract) {
        if (StringUtils.hasText(contract)) {
            for (final CurrencyEnum currencyEnum : CurrencyEnum.values()) {
                if (currencyEnum.getContractAddress().equalsIgnoreCase(contract.toLowerCase())) {
                    return currencyEnum;
                }
            }
        }
        return null;
    }

    public static boolean isErc20(final CurrencyEnum currency) {
        return CurrencyEnum.ERC20_SET.contains(currency);
    }

    public static boolean isATP(final CurrencyEnum currency) {
        return CurrencyEnum.ATP_SET.contains(currency);
    }

    //获得代币对应的主链币种
    public static CurrencyEnum toMainCurrency(CurrencyEnum currencyEnum) {
        if (CurrencyEnum.isErc20(currencyEnum)) {
            return CurrencyEnum.ETH;
        } else if (CurrencyEnum.isATP(currencyEnum)) {
            return CurrencyEnum.ACT;
        } else if (CurrencyEnum.NEO_ASSET.contains(currencyEnum) || CurrencyEnum.NEP5_SET.contains(currencyEnum)) {
            return CurrencyEnum.NEO;
        } else if (CurrencyEnum.ORC_TOKEN_SET.contains(currencyEnum)) {
            return CurrencyEnum.OF;
        } else if (CurrencyEnum.ONT_ASSET.contains(currencyEnum)) {
            return CurrencyEnum.ONT;
        } else if (CurrencyEnum.EOS_ASSET.contains(currencyEnum)) {
            return CurrencyEnum.EOS;
        } else if (CurrencyEnum.RSK_TOKEN_ASSET.contains(currencyEnum)) {
            return CurrencyEnum.RBTC;
        }
        return currencyEnum;
    }

    public long getConfirmNum() {
        return this.confirmNum;
    }

    public long getDepositConfirmNum() {

        return this.depositConfirmNum;
    }

    public long getWithdrawConfirmNum() {

        return this.withdrawConfirmNum;
    }

    public BigDecimal getDecimal() {
        return this.decimal;
    }

    public int getIndex() {
        return this.index;
    }

    public String getName() {
        return this.name;
    }

    public String getContractAddress() {
        return this.remark;
    }

    enum Decimal {
        ZERO(1),
        ONE(10),
        TWO(100),
        THREE(1_000),
        FOUR(10_000),
        FIVE(100_000),
        SIX(1000_000),
        SEVEN(10_000_000L),
        EIGHT(100_000_000L),
        NINE(1_000_000_000L),
        EIGHTEEN(1000_000_000_000_000_000L);
        private final long decimal;

        Decimal(final long dec) {
            this.decimal = dec;
        }

        public long getDecimal() {
            return this.decimal;
        }
    }
}

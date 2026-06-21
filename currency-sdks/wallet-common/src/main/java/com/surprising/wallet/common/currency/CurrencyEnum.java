package com.surprising.wallet.common.currency;

import com.surprising.wallet.common.exception.UnsupportedCurrency;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.surprising.wallet.common.currency.CurrencyEnum.Decimal.*;

/**
 * Legacy wallet routing compatibility only.
 *
 * <p>Do not use this enum as the source of truth for new chains. Runtime currency ids,
 * BIP44 coin types, confirmation policy, fee policy, and network metadata for new chains
 * are loaded from {@code chain_profile}/{@code chain_asset} plus application configuration.
 * Entries remain here only because legacy sharded tables, Redis queues, and signing jobs
 * still route by the historical integer id.</p>
 *
 * @author lilaizhen
 * @data 27/03/2018
 */
public enum CurrencyEnum {

    BTC(1, "btc", 7, 1, 6, EIGHT),
    LTC(24, "ltc", 7, 1, 6, EIGHT, "", 2),
    DOGE(41, "doge", 13, 6, 12, EIGHT, "", 3),
    BCH(42, "bch", 7, 1, 6, EIGHT, "", 145),
    ETH(2, "eth", 121, 12, 120, EIGHTEEN),
    TRX(23, "trx", 121, 12, 120, SIX),
    USDT(100, "usdt", 121, 12, 120, SIX, "0xdac17f958d2ee523a2206206994597c13d831ec7");

    public static final Set<CurrencyEnum> ERC20_SET = Collections.unmodifiableSet(EnumSet.of(USDT));

    private final int index;
    private final String name;
    private final String remark;
    private final long confirmNum;
    private final long depositConfirmNum;
    private final long withdrawConfirmNum;
    private final BigDecimal decimal;
    private final int bip44CoinType;

    CurrencyEnum(final int index, final String name, final int confirmNum, final int depositConfirmNum,
                 final int withdrawConfirmNum, final Decimal decimal) {
        this(index, name, confirmNum, depositConfirmNum, withdrawConfirmNum, decimal, "");
    }

    CurrencyEnum(final int index, final String name, final int confirmNum, final int depositConfirmNum,
                 final int withdrawConfirmNum, final Decimal decimal, final String remark) {
        this(index, name, confirmNum, depositConfirmNum, withdrawConfirmNum, decimal, remark, index);
    }

    CurrencyEnum(final int index, final String name, final int confirmNum, final int depositConfirmNum,
                 final int withdrawConfirmNum, final Decimal decimal, final String remark, final int bip44CoinType) {
        this.index = index;
        this.name = name;
        this.confirmNum = confirmNum;
        this.depositConfirmNum = depositConfirmNum;
        this.withdrawConfirmNum = withdrawConfirmNum;
        this.decimal = BigDecimal.valueOf(decimal.getDecimal());
        this.remark = remark;
        this.bip44CoinType = bip44CoinType;
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
        return currency == USDT;
    }

    public static CurrencyEnum toMainCurrency(CurrencyEnum currencyEnum) {
        if (CurrencyEnum.isErc20(currencyEnum)) {
            return CurrencyEnum.ETH;
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

    /**
     * Legacy currency id and BIP44 coin type are not always the same in this
     * project. New BTC-like chains should use this value for HD derivation while
     * keeping {@link #getIndex()} stable for legacy DB sharding and queues.
     */
    public int getBip44CoinType() {
        return this.bip44CoinType;
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

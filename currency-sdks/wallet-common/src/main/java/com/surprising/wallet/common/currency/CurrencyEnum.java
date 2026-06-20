package com.surprising.wallet.common.currency;

import com.surprising.wallet.common.exception.UnsupportedCurrency;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.surprising.wallet.common.currency.CurrencyEnum.Decimal.*;

/**
 * @author lilaizhen
 * @data 27/03/2018
 */
public enum CurrencyEnum {

    BTC(1, "btc", 7, 1, 6, EIGHT),
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

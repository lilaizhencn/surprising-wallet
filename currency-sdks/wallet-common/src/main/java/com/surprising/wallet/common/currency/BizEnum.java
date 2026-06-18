package com.surprising.wallet.common.currency;

import com.surprising.wallet.common.exception.UnsupportedBiz;

/**
 * @author lilaizhen
 * @data 27/03/2018
 */

public enum BizEnum {
    INTERNAL(0, "internal"),
    SPOT(1, "spot"),
    C2C(2, "c2c"),
    CONTRACT(3, "contract"),
    PORTFOLIO(4, "portfolio"),
    ASSET(5, "asset"),
    PERPETUAL(6, "perpetual");

    private final Integer index;
    private final String name;

    BizEnum(final Integer index, final String name) {
        this.index = index;
        this.name = name;
    }

    public static BizEnum parseBiz(final Integer index) {
        for (final BizEnum biz : BizEnum.values()) {
            if (biz.getIndex() == index) {
                return biz;
            }
        }

        throw new UnsupportedBiz(String.valueOf(index));
    }

    public static BizEnum parseBiz(final String name) {
        for (final BizEnum biz : BizEnum.values()) {
            if (biz.getName().equalsIgnoreCase(name.toLowerCase())) {
                return biz;
            }
        }

        throw new UnsupportedBiz(name);
    }

    public int getIndex() {
        return this.index;
    }

    public String getName() {
        return this.name;
    }


}

package com.surprising.wallet.common.exception;

import com.surprising.commons.support.enums.ErrorCode;
import lombok.Data;

/**
 * @author lilaizhen
 * @data 27/03/2018
 */
@Data
public class UnsupportedCurrency extends RuntimeException implements ErrorCode {
    private static final long serialVersionUID = 6320053538280433220L;
    private String currency;

    public UnsupportedCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public int getCode() {
        return 23001;
    }

    @Override
    public String getMessage() {
        return "Currency " + currency + " is not supported";
    }
}

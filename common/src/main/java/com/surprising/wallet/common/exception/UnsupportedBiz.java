package com.surprising.wallet.common.exception;

import com.surprising.commons.support.enums.ErrorCode;
import lombok.Data;

/**
 * @author lilaizhen
 * @data 27/03/2018
 */
@Data
public class UnsupportedBiz extends RuntimeException implements ErrorCode {
    private static final long serialVersionUID = 1016878537530795545L;
    private String biz;

    public UnsupportedBiz(String biz) {
        this.biz = biz;
    }

    @Override
    public int getCode() {
        return 23002;
    }

    @Override
    public String getMessage() {
        return "Biz " + biz + " is not supported";
    }
}

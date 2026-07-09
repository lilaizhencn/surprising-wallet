package com.surprising.wallet.common.exception;

import com.surprising.commons.support.enums.ErrorCode;

/**
 * @author lilaizhen
 * @data 01/04/2018
 */

public class DataExistError extends RuntimeException implements ErrorCode {
    private static final long serialVersionUID = 1787675315208317965L;
    private final String msg;

    public DataExistError(String msg) {
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return 23004;
    }

    @Override
    public String getMessage() {
        return msg;
    }
}

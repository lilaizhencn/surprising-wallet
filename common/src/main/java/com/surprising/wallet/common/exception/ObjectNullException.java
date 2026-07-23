package com.surprising.wallet.common.exception;

import com.surprising.commons.support.enums.ErrorCode;

/**
 * @author lilaizhen
 * @data 31/03/2018
 */

public class ObjectNullException extends RuntimeException implements ErrorCode {

    private static final long serialVersionUID = -5214481283510953661L;
    private final String msg;

    public ObjectNullException(String msg) {
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return 23003;
    }

    @Override
    public String getMessage() {
        return msg;
    }
}

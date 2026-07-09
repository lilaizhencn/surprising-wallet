package com.surprising.starters.redis.exception;

/**
 * @author lilaizhen
 */
public class LettuceException extends RuntimeException {
    private static final long serialVersionUID = 7250344708231626488L;

    /**
     * @param msg
     */
    public LettuceException(String msg) {
        super(msg);
    }

    /**
     * @param msg
     * @param t
     */
    public LettuceException(String msg, Throwable t) {
        super(msg, t);
    }


    public LettuceException(Throwable t) {
        super(t);
    }
}

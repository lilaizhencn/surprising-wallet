package com.surprising.common;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author lilaizhen
 */
@ToString
@NoArgsConstructor
public class RespEntity {

    private static final Integer SUCCESSCODE = 200;
    @Getter
    @Setter
    public Object attachment;
    @Getter
    @Setter
    public int status;
    @Getter
    @Setter
    public String message;

    public RespEntity(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public RespEntity(Object attachment, int status, String message) {
        this.attachment = attachment;
        this.status = status;
        this.message = message;
    }

    public RespEntity(Object attachment, int status) {
        this.attachment = attachment;
        this.status = status;
    }

    public static RespEntity success() {
        return new RespEntity(null, SUCCESSCODE);
    }

    public static RespEntity success(Object object) {
        return new RespEntity(object, SUCCESSCODE);
    }

    public static RespEntity error(RespCode respCode) {
        return new RespEntity(respCode.getErrorCode(), respCode.getErrorMsg());
    }

    public static RespEntity error(RespCode respCode, Object extend) {
        return new RespEntity(extend, respCode.getErrorCode(), respCode.getErrorMsg());
    }
}

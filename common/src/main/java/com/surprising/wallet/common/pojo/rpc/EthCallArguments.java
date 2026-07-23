package com.surprising.wallet.common.pojo.rpc;

import lombok.Data;

import java.io.Serializable;

/**
 * Created by lilaizhen on 2018/12/6.
 */
@Data
public class EthCallArguments implements Serializable {
    public String from;
    public String to;
    public String gas;
    public String gasPrice;
    public String value;
    public String data;
    public String nonce;

    public EthCallArguments() {
    }

    @Override
    public String toString() {
        return "EthCallArguments{from='" + this.from + '\'' + ", to='" + this.to + '\'' + ", gasLimit='" + this.gas + '\''
                + ", gasPrice='" + this.gasPrice + '\'' + ", value='" + this.value + '\'' + ", data='" + this.data + '\''
                + ", nonce='" + this.nonce + '\'' + '}';
    }
}

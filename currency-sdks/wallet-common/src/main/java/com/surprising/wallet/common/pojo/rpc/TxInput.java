package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * @author lilaizhen
 * @data 31/03/2018
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class TxInput implements Serializable {
    private static final long serialVersionUID = -7299071098557651611L;
    private String coinbase;
    private String txid;
    private int vout;
    private int n;
    private long sequence;
    private TxOutput prev_out;
    private ScriptSig scriptSig;
    private long value;
    private String legacyAddress;
    private String cashAddress;
}

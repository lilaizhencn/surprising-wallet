package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @author lilaizhen
 * @data 31/03/2018
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class BtcLikeRawTransaction implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    protected long out = 0;
    protected long in = 0;
    private String txid;
    private int confirmations;
    private long time;
    private long received_time;
    private String relayed_by;
    private int size;
    private int version;
    private long locktime;
    private String blockhash;
    private long blocktime;
    private long blockheight;

    private List<TxInput> vin;
    private List<TxOutput> vout;

    private String hex;
}

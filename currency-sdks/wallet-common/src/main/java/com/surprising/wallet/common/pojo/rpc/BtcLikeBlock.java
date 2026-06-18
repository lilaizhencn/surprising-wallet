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
public class BtcLikeBlock implements Serializable {
    private List<String> tx;
    private long time;
    private long height;
    //private long nonce;
    private String hash;
    private String bits;
    private long difficulty;
    private String merkleroot;
    private String previousblockhash;
    private String nextblockhash;
    private long confirmations;
    private long version;
    private long size;
}

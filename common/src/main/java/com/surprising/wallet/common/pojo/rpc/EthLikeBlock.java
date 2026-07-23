package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EthLikeBlock implements Serializable {
    private String hash;
    private String number;
    private String parentHash;
    private String nonce;
    private String sha3Uncles;
    private String logsBloom;
    private String transactionsRoot;
    private String stateRoot;
    private String miner;
    private String difficulty;
    private String totalDifficulty;
    private String extraData;
    private String size;
    private String gasLimit;
    private String gasUsed;
    private String mixHash;
    private String timestamp;
    private EthRawTransaction[] transactions;
    private String[] uncles;
}

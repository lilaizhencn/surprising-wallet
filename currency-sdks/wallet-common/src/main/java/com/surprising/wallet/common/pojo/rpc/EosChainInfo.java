package com.surprising.wallet.common.pojo.rpc;

import lombok.Data;

/**
 * @author lilaizhen
 * @data 2018/6/4
 */

@Data
public class EosChainInfo {
    private String serverInfo;
    private String chainId;
    private Long headBlockNum;
    private String headBlockId;
    private String headBlockProducer;
}

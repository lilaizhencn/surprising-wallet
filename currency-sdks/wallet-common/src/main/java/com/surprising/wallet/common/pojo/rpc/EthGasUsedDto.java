package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * Created by lilaizhen on 2018/2/8.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EthGasUsedDto implements Serializable {
    private String transactionHash;
    private String transactionIndex;
    private String blockHash;
    private String cumulativeGasUsed;
    private String gasUsed;
    private String contractAddress;
    private String logs;
    private String logsBloom;
}

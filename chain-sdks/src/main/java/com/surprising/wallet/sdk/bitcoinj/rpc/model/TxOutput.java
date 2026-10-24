package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class TxOutput implements Serializable {
    private BigDecimal value;
    private int n;
    private boolean spent;
    /**
     * 保存 {@code scriptPubKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private ScriptPubKey scriptPubKey;

}

package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class TxOutput implements Serializable {
    /**
     * 保存 {@code value}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal value;
    /**
     * 保存 {@code n}，用于承载当前对象的运行配置或业务数据。
     */
    private int n;
    /**
     * 保存 {@code spent}，用于承载当前对象的运行配置或业务数据。
     */
    private boolean spent;
    /**
     * 保存 {@code scriptPubKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private ScriptPubKey scriptPubKey;

}

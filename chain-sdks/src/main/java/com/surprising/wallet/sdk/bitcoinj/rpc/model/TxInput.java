package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class TxInput implements Serializable {
    /**
     * 保存 {@code serialVersionUID}，用于标识交易、区块或业务记录。
     */
    private static final long serialVersionUID = -7299071098557651611L;
    /**
     * 保存 {@code coinbase}，表示链、网络、资产或代币配置。
     */
    private String coinbase;
    /**
     * 保存 {@code txid}，用于标识交易、区块或业务记录。
     */
    private String txid;
    /**
     * 保存 {@code vout}，用于承载当前对象的运行配置或业务数据。
     */
    private int vout;
    /**
     * 保存 {@code n}，用于承载当前对象的运行配置或业务数据。
     */
    private int n;
    /**
     * 保存 {@code sequence}，用于承载当前对象的运行配置或业务数据。
     */
    private long sequence;
    /**
     * 保存 {@code prev_out}，用于承载当前对象的运行配置或业务数据。
     */
    private TxOutput prev_out;
    /**
     * 保存 {@code scriptSig}，用于承载当前对象的运行配置或业务数据。
     */
    private ScriptSig scriptSig;
    /**
     * 保存 {@code value}，用于保存金额、费用或链上执行状态。
     */
    private long value;
    /**
     * 保存 {@code legacyAddress}，表示链、网络、资产或代币配置。
     */
    private String legacyAddress;
    /**
     * 保存 {@code cashAddress}，表示链、网络、资产或代币配置。
     */
    private String cashAddress;
}

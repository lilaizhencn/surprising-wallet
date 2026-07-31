package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class BtcLikeRawTransaction implements Serializable {

    /**
     * 保存 {@code serialVersionUID}，用于标识交易、区块或业务记录。
     */
    private static final long serialVersionUID = 1L;
    /**
     * 保存 {@code out}，用于承载当前对象的运行配置或业务数据。
     */
    protected long out = 0;
    /**
     * 保存 {@code in}，用于承载当前对象的运行配置或业务数据。
     */
    protected long in = 0;
    /**
     * 保存 {@code txid}，用于标识交易、区块或业务记录。
     */
    private String txid;
    /**
     * 保存 {@code confirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private int confirmations;
    /**
     * 保存 {@code time}，用于记录时间边界或审计时间。
     */
    private long time;
    /**
     * 保存 {@code received_time}，用于记录时间边界或审计时间。
     */
    private long received_time;
    /**
     * 保存 {@code relayed_by}，用于保存业务集合或索引状态。
     */
    private String relayed_by;
    /**
     * 保存 {@code size}，用于承载当前对象的运行配置或业务数据。
     */
    private int size;
    /**
     * 保存 {@code version}，用于承载当前对象的运行配置或业务数据。
     */
    private int version;
    /**
     * 保存 {@code locktime}，用于记录时间边界或审计时间。
     */
    private long locktime;
    /**
     * 保存 {@code blockhash}，用于标识交易、区块或业务记录。
     */
    private String blockhash;
    /**
     * 保存 {@code blocktime}，用于标识交易、区块或业务记录。
     */
    private long blocktime;
    /**
     * 保存 {@code blockheight}，用于标识交易、区块或业务记录。
     */
    private long blockheight;

    /**
     * 保存 {@code vin}，用于承载当前对象的运行配置或业务数据。
     */
    private List<TxInput> vin;
    /**
     * 保存 {@code vout}，用于承载当前对象的运行配置或业务数据。
     */
    private List<TxOutput> vout;

    /**
     * 保存 {@code hex}，用于承载当前对象的运行配置或业务数据。
     */
    private String hex;
}

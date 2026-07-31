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
public class BtcLikeBlock implements Serializable {
    /**
     * 保存 {@code tx}，用于标识交易、区块或业务记录。
     */
    private List<String> tx;
    /**
     * 保存 {@code time}，用于记录时间边界或审计时间。
     */
    private long time;
    /**
     * 保存 {@code height}，用于标识交易、区块或业务记录。
     */
    private long height;
    //private long nonce;
    /**
     * 保存 {@code hash}，用于标识交易、区块或业务记录。
     */
    private String hash;
    /**
     * 保存 {@code bits}，用于承载当前对象的运行配置或业务数据。
     */
    private String bits;
    /**
     * 保存 {@code difficulty}，用于承载当前对象的运行配置或业务数据。
     */
    private long difficulty;
    /**
     * 保存 {@code merkleroot}，用于承载当前对象的运行配置或业务数据。
     */
    private String merkleroot;
    /**
     * 保存 {@code previousblockhash}，用于标识交易、区块或业务记录。
     */
    private String previousblockhash;
    /**
     * 保存 {@code nextblockhash}，用于标识交易、区块或业务记录。
     */
    private String nextblockhash;
    /**
     * 保存 {@code confirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private long confirmations;
    /**
     * 保存 {@code version}，用于承载当前对象的运行配置或业务数据。
     */
    private long version;
    /**
     * 保存 {@code size}，用于承载当前对象的运行配置或业务数据。
     */
    private long size;
}

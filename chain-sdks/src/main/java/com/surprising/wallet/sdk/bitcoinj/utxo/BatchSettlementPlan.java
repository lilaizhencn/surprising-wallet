package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Collections;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class BatchSettlementPlan {
    /**
     * 保存 {@code inputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<UtxoCandidate> inputs;
    /**
     * 保存 {@code outputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<WithdrawSettlementOutput> outputs;
    /**
     * 保存 {@code feeSat}，用于保存金额、费用或链上执行状态。
     */
    private final long feeSat;
    /**
     * 保存 {@code changeSat}，用于承载当前对象的运行配置或业务数据。
     */
    private final long changeSat;
    /**
     * 保存 {@code totalRequestedSat}，用于承载当前对象的运行配置或业务数据。
     */
    private final long totalRequestedSat;

    /**
     * 构造 {@code BatchSettlementPlan}，初始化该组件运行所需的状态和依赖。
     */
    public BatchSettlementPlan(List<UtxoCandidate> inputs, List<WithdrawSettlementOutput> outputs,
                               long feeSat, long changeSat, long totalRequestedSat) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.feeSat = feeSat;
        this.changeSat = changeSat;
        this.totalRequestedSat = totalRequestedSat;
    }

    /**
     * 获取或查询 {@code getInputs} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoCandidate> getInputs() {
        return Collections.unmodifiableList(inputs);
    }

    /**
     * 获取或查询 {@code getOutputs} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawSettlementOutput> getOutputs() {
        return Collections.unmodifiableList(outputs);
    }

    /**
     * 获取或查询 {@code getFeeSat} 对应的数据，供调用方读取当前状态。
     */
    public long getFeeSat() {
        return feeSat;
    }

    /**
     * 获取或查询 {@code getChangeSat} 对应的数据，供调用方读取当前状态。
     */
    public long getChangeSat() {
        return changeSat;
    }

    /**
     * 获取或查询 {@code getTotalRequestedSat} 对应的数据，供调用方读取当前状态。
     */
    public long getTotalRequestedSat() {
        return totalRequestedSat;
    }
}

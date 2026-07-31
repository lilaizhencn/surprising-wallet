package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Collections;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class UtxoSelection {
    /**
     * 保存 {@code selected}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<UtxoCandidate> selected;
    /**
     * 保存 {@code targetSat}，用于承载当前对象的运行配置或业务数据。
     */
    private final long targetSat;
    /**
     * 保存 {@code inputSat}，用于承载当前对象的运行配置或业务数据。
     */
    private final long inputSat;
    /**
     * 保存 {@code feeSat}，用于保存金额、费用或链上执行状态。
     */
    private final long feeSat;
    /**
     * 保存 {@code changeSat}，用于承载当前对象的运行配置或业务数据。
     */
    private final long changeSat;
    /**
     * 保存 {@code exactMatch}，用于承载当前对象的运行配置或业务数据。
     */
    private final boolean exactMatch;

    /**
     * 构造 {@code UtxoSelection}，初始化该组件运行所需的状态和依赖。
     */
    public UtxoSelection(List<UtxoCandidate> selected, long targetSat, long inputSat,
                         long feeSat, long changeSat, boolean exactMatch) {
        this.selected = List.copyOf(selected);
        this.targetSat = targetSat;
        this.inputSat = inputSat;
        this.feeSat = feeSat;
        this.changeSat = changeSat;
        this.exactMatch = exactMatch;
    }

    /**
     * 获取或查询 {@code getSelected} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoCandidate> getSelected() {
        return Collections.unmodifiableList(selected);
    }

    /**
     * 获取或查询 {@code getTargetSat} 对应的数据，供调用方读取当前状态。
     */
    public long getTargetSat() {
        return targetSat;
    }

    /**
     * 获取或查询 {@code getInputSat} 对应的数据，供调用方读取当前状态。
     */
    public long getInputSat() {
        return inputSat;
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
     * 判断 {@code isExactMatch} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isExactMatch() {
        return exactMatch;
    }

    /**
     * 获取或查询 {@code getInputCount} 对应的数据，供调用方读取当前状态。
     */
    public long getInputCount() {
        return selected.size();
    }
}

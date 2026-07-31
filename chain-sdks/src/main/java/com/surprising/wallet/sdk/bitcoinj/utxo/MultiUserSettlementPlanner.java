package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class MultiUserSettlementPlanner {
    /**
     * 保存 {@code optimizer}，用于承载当前对象的运行配置或业务数据。
     */
    private final UtxoOptimizer optimizer;

    /**
     * 构造 {@code MultiUserSettlementPlanner}，初始化该组件运行所需的状态和依赖。
     */
    public MultiUserSettlementPlanner() {
        this(new UtxoOptimizer());
    }

    /**
     * 构造 {@code MultiUserSettlementPlanner}，初始化该组件运行所需的状态和依赖。
     */
    public MultiUserSettlementPlanner(UtxoOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * 构建或生成 {@code plan} 对应的结果，并执行输入和状态校验。
     */
    public BatchSettlementPlan plan(List<UtxoCandidate> candidates, List<WithdrawSettlementOutput> outputs,
                                    long feeRateSatPerVByte, long dustThresholdSat) {
        ArrayList<WithdrawSettlementOutput> normalized = new ArrayList<>(outputs);
        normalized.sort(Comparator.comparingLong(WithdrawSettlementOutput::getUserId)
                .thenComparing(WithdrawSettlementOutput::getAddress)
                .thenComparingLong(WithdrawSettlementOutput::getSatoshis));
        return optimizer.planBatch(candidates, normalized, feeRateSatPerVByte, dustThresholdSat);
    }
}

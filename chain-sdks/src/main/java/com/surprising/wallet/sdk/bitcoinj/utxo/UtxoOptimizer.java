package com.surprising.wallet.sdk.bitcoinj.utxo;

import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UTXO 选择器：小规模输入使用精确搜索，超出上限后使用贪心策略，并结合手续费和粉尘阈值计算找零。
 */
public final class UtxoOptimizer {
    /**
     * 定义 {@code EXACT_SEARCH_LIMIT} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int EXACT_SEARCH_LIMIT = 18;

    /**
     * 按默认单输出和 P2WSH 粉尘阈值选择能够覆盖目标金额及手续费的 UTXO。
     */
    public UtxoSelection select(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte) {
        return select(candidates, targetSat, feeRateSatPerVByte, 1, P2wshFeeCalculator.DUST_THRESHOLD_SAT);
    }

    /**
     * 选择 UTXO：候选数量较少时执行精确搜索，否则使用按金额降序的贪心选择。
     * <p>选择结果会同时计算手续费、找零，并将低于粉尘阈值的找零并入手续费。</p>
     */
    public UtxoSelection select(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte,
                                int recipientOutputs, long dustThresholdSat) {
        validateInputs(candidates, targetSat, feeRateSatPerVByte, recipientOutputs, dustThresholdSat);
        ArrayList<UtxoCandidate> normalized = new ArrayList<>(candidates);
        normalized.sort(Comparator.naturalOrder());

        UtxoSelection bestExact = normalized.size() <= EXACT_SEARCH_LIMIT
                ? searchExact(normalized, targetSat, feeRateSatPerVByte, recipientOutputs, dustThresholdSat)
                : null;
        if (bestExact != null) {
            return bestExact;
        }
        return greedySelect(normalized, targetSat, feeRateSatPerVByte, recipientOutputs, dustThresholdSat);
    }

    /**
     * 为多笔提现输出规划一批 UTXO 结算方案，并确认输入足以覆盖输出和手续费。
     */
    public BatchSettlementPlan planBatch(List<UtxoCandidate> candidates, List<WithdrawSettlementOutput> outputs,
                                         long feeRateSatPerVByte, long dustThresholdSat) {
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty");
        }
        long targetSat = outputs.stream().mapToLong(WithdrawSettlementOutput::getSatoshis).sum();
        UtxoSelection selection = select(candidates, targetSat, feeRateSatPerVByte, outputs.size(), dustThresholdSat);
        if (selection.getInputSat() < targetSat + selection.getFeeSat()) {
            throw new IllegalStateException("selected UTXOs do not cover the settlement batch");
        }
        return new BatchSettlementPlan(selection.getSelected(), outputs, selection.getFeeSat(),
                selection.getChangeSat(), targetSat);
    }

    /**
     * 校验候选 UTXO、目标金额、费率、输出数量和粉尘阈值均为可处理范围。
     */
    private static void validateInputs(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte,
                                       int recipientOutputs, long dustThresholdSat) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        if (targetSat <= 0) {
            throw new IllegalArgumentException("targetSat must be positive");
        }
        if (feeRateSatPerVByte <= 0) {
            throw new IllegalArgumentException("feeRateSatPerVByte must be positive");
        }
        if (recipientOutputs <= 0) {
            throw new IllegalArgumentException("recipientOutputs must be positive");
        }
        if (dustThresholdSat <= 0) {
            throw new IllegalArgumentException("dustThresholdSat must be positive");
        }
    }

    /**
     * 在候选数量未超过上限时枚举组合，寻找浪费金额最小且输入数量更少的方案。
     */
    private UtxoSelection searchExact(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte,
                                      int recipientOutputs, long dustThresholdSat) {
        BestHolder best = new BestHolder();
        depthFirstSearch(candidates, 0, new ArrayList<>(), 0L, targetSat, feeRateSatPerVByte,
                recipientOutputs, dustThresholdSat, best);
        return best.selection;
    }

    /**
     * 深度优先枚举候选组合，并持续保留当前最优结算方案。
     */
    private void depthFirstSearch(List<UtxoCandidate> candidates, int start, List<UtxoCandidate> chosen,
                                  long inputSat, long targetSat, long feeRateSatPerVByte, int recipientOutputs,
                                  long dustThresholdSat, BestHolder best) {
        if (!chosen.isEmpty()) {
            CandidateEvaluation evaluation = evaluate(chosen, inputSat, targetSat, feeRateSatPerVByte,
                    recipientOutputs, dustThresholdSat);
            if (evaluation != null && evaluation.covered() && betterThan(evaluation, best)) {
                best.selection = evaluation.toSelection();
            }
        }
        if (start >= candidates.size()) {
            return;
        }
        for (int i = start; i < candidates.size(); i++) {
            UtxoCandidate candidate = candidates.get(i);
            chosen.add(candidate);
            depthFirstSearch(candidates, i + 1, chosen, inputSat + candidate.getSatoshis(), targetSat,
                    feeRateSatPerVByte, recipientOutputs, dustThresholdSat, best);
            chosen.remove(chosen.size() - 1);
        }
    }

    /**
     * 按 UTXO 金额从大到小逐个选择，直到输入金额覆盖目标输出和手续费。
     */
    private UtxoSelection greedySelect(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte,
                                       int recipientOutputs, long dustThresholdSat) {
        ArrayList<UtxoCandidate> descending = new ArrayList<>(candidates);
        descending.sort(Comparator.comparingLong(UtxoCandidate::getSatoshis).reversed()
                .thenComparing(UtxoCandidate::getTxId)
                .thenComparingInt(UtxoCandidate::getIndex));

        ArrayList<UtxoCandidate> chosen = new ArrayList<>();
        long inputSat = 0L;
        long feeSat = 0L;
        long changeSat = 0L;
        for (UtxoCandidate candidate : descending) {
            chosen.add(candidate);
            inputSat += candidate.getSatoshis();
            CandidateEvaluation evaluation = evaluate(chosen, inputSat, targetSat, feeRateSatPerVByte,
                    recipientOutputs, dustThresholdSat);
            if (evaluation != null) {
                feeSat = evaluation.feeSat();
                changeSat = evaluation.changeSat();
                if (evaluation.covered()) {
                    return evaluation.toSelection();
                }
            }
        }
        CandidateEvaluation evaluation = evaluate(chosen, inputSat, targetSat, feeRateSatPerVByte,
                recipientOutputs, dustThresholdSat);
        if (evaluation == null || !evaluation.covered()) {
            throw new IllegalArgumentException("insufficient balance to cover target and fee");
        }
        return evaluation.toSelection();
    }

    /**
     * 根据候选输入数量和输出数量计算手续费、找零及是否覆盖目标金额。
     */
    private CandidateEvaluation evaluate(List<UtxoCandidate> chosen, long inputSat, long targetSat,
                                         long feeRateSatPerVByte, int recipientOutputs, long dustThresholdSat) {
        if (chosen.isEmpty()) {
            return null;
        }
        long feeWithChange = P2wshFeeCalculator.calculateFeeSat(chosen.size(), recipientOutputs + 1, feeRateSatPerVByte);
        long changeSat = inputSat - targetSat - feeWithChange;
        long feeSat = feeWithChange;
        boolean exact = false;
        if (changeSat > 0 && changeSat < dustThresholdSat) {
            feeSat = inputSat - targetSat;
            changeSat = 0L;
            exact = true;
        }
        boolean covered = inputSat >= targetSat + feeSat;
        return new CandidateEvaluation(new ArrayList<>(chosen), targetSat, inputSat, feeSat, changeSat, exact, covered);
    }

    /**
     * 按浪费金额、输入数量和字典序比较两个候选方案，保证选择结果稳定。
     */
    private boolean betterThan(CandidateEvaluation next, BestHolder best) {
        if (best.selection == null) {
            return true;
        }
        UtxoSelection current = best.selection;
        long nextWaste = next.inputSat - next.targetSat - next.feeSat;
        long currentWaste = current.getInputSat() - current.getTargetSat() - current.getFeeSat();
        if (nextWaste != currentWaste) {
            return nextWaste < currentWaste;
        }
        if (next.chosen.size() != current.getSelected().size()) {
            return next.chosen.size() < current.getSelected().size();
        }
        return lexicographicallySmaller(next.chosen, current.getSelected());
    }

    /**
     * 比较两个已排序 UTXO 列表的字典序，用于稳定选择同等成本方案。
     */
    private boolean lexicographicallySmaller(List<UtxoCandidate> left, List<UtxoCandidate> right) {
        int size = Math.min(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return left.size() < right.size();
    }

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static final class BestHolder {
        /**
         * 保存 {@code selection}，用于承载当前对象的运行配置或业务数据。
         */
        private UtxoSelection selection;
    }

    private record CandidateEvaluation(List<UtxoCandidate> chosen, long targetSat, long inputSat,
                                       long feeSat, long changeSat, boolean exact, boolean covered) {
        /**
         * 编码 {@code toSelection} 对应的数据，生成链上或接口所需的表示。
         */
        private UtxoSelection toSelection() {
            return new UtxoSelection(chosen, targetSat, inputSat, feeSat, changeSat, exact);
        }
    }
}

package com.surprising.wallet.sdk.bitcoinj.utxo;

import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hybrid knapsack + greedy UTXO selector.
 */
public final class UtxoOptimizer {
    private static final int EXACT_SEARCH_LIMIT = 18;

    public UtxoSelection select(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte) {
        return select(candidates, targetSat, feeRateSatPerVByte, 1, P2wshFeeCalculator.DUST_THRESHOLD_SAT);
    }

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

    private UtxoSelection searchExact(List<UtxoCandidate> candidates, long targetSat, long feeRateSatPerVByte,
                                      int recipientOutputs, long dustThresholdSat) {
        BestHolder best = new BestHolder();
        depthFirstSearch(candidates, 0, new ArrayList<>(), 0L, targetSat, feeRateSatPerVByte,
                recipientOutputs, dustThresholdSat, best);
        return best.selection;
    }

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

    private static final class BestHolder {
        private UtxoSelection selection;
    }

    private record CandidateEvaluation(List<UtxoCandidate> chosen, long targetSat, long inputSat,
                                       long feeSat, long changeSat, boolean exact, boolean covered) {
        private UtxoSelection toSelection() {
            return new UtxoSelection(chosen, targetSat, inputSat, feeSat, changeSat, exact);
        }
    }
}

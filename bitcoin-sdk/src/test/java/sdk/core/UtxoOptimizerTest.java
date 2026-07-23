package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.utxo.BatchSettlementPlan;
import com.surprising.wallet.sdk.bitcoinj.utxo.MultiUserSettlementPlanner;
import com.surprising.wallet.sdk.bitcoinj.utxo.UtxoCandidate;
import com.surprising.wallet.sdk.bitcoinj.utxo.UtxoOptimizer;
import com.surprising.wallet.sdk.bitcoinj.utxo.UtxoSelection;
import com.surprising.wallet.sdk.bitcoinj.utxo.WithdrawSettlementOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtxoOptimizerTest {
    @Test
    void optimizerShouldPreferBestExactSubsetAndRemainDeterministic() {
        UtxoOptimizer optimizer = new UtxoOptimizer();
        List<UtxoCandidate> candidates = List.of(
                new UtxoCandidate("c", 0, 50_000L),
                new UtxoCandidate("a", 0, 80_000L),
                new UtxoCandidate("b", 0, 30_000L)
        );

        UtxoSelection selection = optimizer.select(candidates, 70_000L, 1L, 2, P2wshFeeCalculator.DUST_THRESHOLD_SAT);

        assertEquals(2, selection.getSelected().size());
        assertEquals("b", selection.getSelected().get(0).getTxId());
        assertEquals("c", selection.getSelected().get(1).getTxId());
        assertTrue(selection.getInputSat() >= selection.getTargetSat() + selection.getFeeSat());
    }

    @Test
    void optimizerShouldMergeDustChangeIntoFee() {
        UtxoOptimizer optimizer = new UtxoOptimizer();
        List<UtxoCandidate> candidates = List.of(new UtxoCandidate("tx1", 0, 100_500L));

        UtxoSelection selection = optimizer.select(candidates, 100_000L, 1L, 1, P2wshFeeCalculator.DUST_THRESHOLD_SAT);

        assertEquals(0L, selection.getChangeSat());
        assertEquals(500L, selection.getFeeSat());
        assertTrue(selection.isExactMatch());
    }

    @Test
    void batchSettlementPlannerShouldKeepPerUserOutputsAndAtomicTotals() {
        MultiUserSettlementPlanner planner = new MultiUserSettlementPlanner();
        List<UtxoCandidate> candidates = List.of(
                new UtxoCandidate("tx2", 1, 40_000L),
                new UtxoCandidate("tx1", 0, 60_000L),
                new UtxoCandidate("tx3", 0, 20_000L)
        );
        List<WithdrawSettlementOutput> outputs = List.of(
                new WithdrawSettlementOutput(2L, "tb1qsecond", 25_000L),
                new WithdrawSettlementOutput(1L, "tb1qfirst", 30_000L)
        );

        BatchSettlementPlan plan = planner.plan(candidates, outputs, 1L, P2wshFeeCalculator.DUST_THRESHOLD_SAT);

        assertEquals(2, plan.getOutputs().size());
        assertEquals(1L, plan.getOutputs().get(0).getUserId());
        assertEquals(2L, plan.getOutputs().get(1).getUserId());
        assertTrue(plan.getInputs().size() >= 1);
        assertTrue(plan.getTotalRequestedSat() > 0);
        assertTrue(plan.getFeeSat() >= 0);
        assertFalse(plan.getInputs().isEmpty());
    }
}

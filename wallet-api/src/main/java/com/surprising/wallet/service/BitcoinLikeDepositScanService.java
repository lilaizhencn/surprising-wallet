package com.surprising.wallet.service;

import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 读取 RPC 事实，显式调用充值事务；不持有可变扫描上下文。 */
@Service
@RequiredArgsConstructor
public class BitcoinLikeDepositScanService {
    private static final int FINALITY_AUDIT_DEPTH = 256;
    private final BitcoinLikeChainRuntime runtime;
    private final ChainProfileRepository profiles;
    private final ChainScanHeightRepository heights;
    private final ChainScanBlockRepository blocks;
    private final UtxoRepository utxos;
    private final UtxoDepositService posting;
    private final WalletRuntimeConfigService runtimeConfig;

    public void scan(String chain) {
        if (!runtimeConfig.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) return;
        var profile = profiles.findAccountByChain(chain).orElseThrow();
        var type = ChainType.valueOf(profile.getChain());
        long best = runtime.bestHeight(type);
        String scanner = UtxoDepositService.scannerName(profile);
        long configuredStart = runtimeConfig.scanStartHeight(chain);
        long stored = heights.findSafeHeight(chain, scanner)
                .orElse(configuredStart > 0 ? configuredStart - 1 : 0L);
        if (stored <= 0 && configuredStart <= 0) {
            heights.upsert(chain, scanner, best, best);
            return;
        }
        long begin = Math.max(0, stored + 1);
        long maximum = runtimeConfig.scanMaxBlocksPerRun(chain);
        long end = maximum > 0 ? Math.min(best, begin + maximum - 1) : best;
        for (long height = begin; height <= end; height++) {
            if (!runtimeConfig.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) return;
            var block = runtime.observeBlock(type, height);
            if (block.isEmpty()) return;
            posting.scanBlock(profile, block.orElseThrow());
        }
    }

    /** 已观察充值的重组核对和补确认，不受新扫描开关影响。 */
    public void refresh(AccountChainProfile profile) {
        String chain = profile.getChain();
        var type = ChainType.valueOf(chain);
        long minimum = Math.max(0, runtime.bestHeight(type) - FINALITY_AUDIT_DEPTH + 1);
        for (var observed : blocks.listObserved(chain, UtxoDepositService.CANONICAL_SCANNER, minimum)) {
            if (observed.hash().equalsIgnoreCase(runtime.blockHash(type, observed.height()))) continue;
            var block = runtime.observeBlock(type, observed.height())
                    .orElseThrow(() -> new IllegalStateException("canonical UTXO block missing at " + observed.height()));
            posting.reconcileBlock(profile, block);
        }
        long afterId = 0;
        int limit = 500;
        var confirmations = new java.util.HashMap<String, Integer>();
        while (true) {
            var pending = utxos.listPendingConfirmations(chain, profile.getNativeSymbol(),
                    profile.getDepositConfirmations(), afterId, limit);
            for (var row : pending) posting.refresh(profile, row, confirmations.computeIfAbsent(
                    row.output().txHash(), txHash -> runtime.confirmations(type, txHash)));
            if (pending.size() < limit) return;
            afterId = pending.getLast().id();
        }
    }
}

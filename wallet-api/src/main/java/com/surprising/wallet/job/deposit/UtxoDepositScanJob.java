package com.surprising.wallet.job.deposit;

import com.surprising.wallet.config.WalletRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * UTXO 链（BTC/BCH/LTC/DOGE）充值扫描调度。
 * <p>
 * 每 5 秒检查一次：BTC/BCH/LTC/DOGE 分别按自身出块速度到期扫描。
 * 实际 RPC 扫描仍保持串行，避免共享扫描器上下文并发污染。
 *
 * @author atomex
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtxoDepositScanJob {

    /** 支持扫描的 UTXO 链列表。 */
    private static final List<String> CHAINS = List.of("BTC", "BCH", "LTC", "DOGE");

    /** 区块扫描器。 */
    private final ScanBlockJob scanBlockJob;
    /** 数据库运行时总开关、链开关及统一扫描周期策略。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** 每条 UTXO 链下次允许扫描的时间。 */
    private final ConcurrentMap<String, Long> nextScanAtMillis = new ConcurrentHashMap<>();

    /**
     * 高频轻量检查、按链到期扫描；关闭后清理节流状态，重新开启可在下一轮立即执行。
     */
    @Scheduled(
            scheduler = "depositTaskScheduler",
            fixedDelayString = "${sw.wallet.utxo.deposit-schedule-check-delay:5000}")
    public void execute() {
        if (!runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN)) {
            nextScanAtMillis.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (String chain : CHAINS) {
            if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) {
                nextScanAtMillis.remove(chain);
                continue;
            }
            if (now < nextScanAtMillis.getOrDefault(chain, 0L)) {
                continue;
            }
            try {
                scanBlockJob.scan(chain);
            } catch (Throwable e) {
                log.error("UTXO scan failed for chain {}: {}", chain, e.getMessage(), e);
            } finally {
                nextScanAtMillis.put(
                        chain, System.currentTimeMillis() + runtimeConfigService.scanIntervalMillis(chain));
            }
        }
    }
}

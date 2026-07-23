package com.surprising.wallet.job.deposit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UTXO 链（BTC/BCH/LTC/DOGE）充值扫描调度。
 * <p>
 * 每 15 秒执行一次：依次扫描四条 UTXO 链的当前区块，识别充值交易并写入数据库。
 * 串行扫描天然避免了旧架构中四个独立 {@code Scan*BlockJob} 用偏移 cron 错峰的问题。
 *
 * @author atomex
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtxoDepositScanJob {

    private static final List<String> CHAINS = List.of("BTC", "BCH", "LTC", "DOGE");

    private final ScanBlockJob scanBlockJob;

    @Scheduled(scheduler = "depositTaskScheduler", fixedDelay = 150_000)
    public void execute() {
        for (String chain : CHAINS) {
            try {
                scanBlockJob.scan(chain);
            } catch (Throwable e) {
                log.error("UTXO scan failed for chain {}: {}", chain, e.getMessage(), e);
            }
        }
    }
}

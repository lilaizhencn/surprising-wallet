package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.AccountChainWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Account-Chain 入金扫描任务，按链出块速度拉取充值交易。
 */
public class AccountChainDepositScanJob {

    /** 账号链流程编排服务。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * Account-Chain 充值扫描任务。
     * <p>
     * 每秒检查一次：仅扫描达到各自出块节流周期且开关有效的 account-chain 链
     * （SOLANA、TRON、APTOS、SUI、TON、XRP、ADA、NEAR 及 EVM 非 7702 链），
     * 调用对应的链适配器逐块扫描充值交易并写入 deposit_record。
     * <p>
     * 注意：BTC/BCH/LTC/DOGE 等 UTXO 链由独立的 {@code ScanBlockJob} 子类处理。
     */
    @Scheduled(
            scheduler = "accountTaskScheduler",
            fixedDelayString = "${sw.wallet.account.deposit-schedule-check-delay:1000}")
    public void run() {
        log.debug("AccountChain deposit scan job begin");
        workflowService.scanDueDeposits();
        log.debug("AccountChain deposit scan job end");
    }
}

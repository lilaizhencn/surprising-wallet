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
 * Account-Chain 入金扫描任务，按固定周期拉取各链充值交易。
 */
public class AccountChainDepositScanJob {

    /** 账号链流程编排服务。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * Account-Chain 充值扫描任务。
     * <p>
     * 每 30 秒（offset 11s）执行一次：遍历所有启用的 account-chain 链
     * （SOLANA、TRON、APTOS、SUI、TON、XRP、ADA、NEAR 及 EVM 非 7702 链），
     * 调用对应的链适配器逐块扫描充值交易并写入 deposit_record。
     * <p>
     * 注意：BTC/BCH/LTC/DOGE 等 UTXO 链由独立的 {@code ScanBlockJob} 子类处理。
     */
    @Scheduled(scheduler = "accountTaskScheduler", cron = "11/30 * * * * ?")
    public void run() {
        log.debug("AccountChain deposit scan job begin");
        workflowService.scanDeposits();
        log.debug("AccountChain deposit scan job end");
    }
}

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
 * Account-Chain 归集调度任务，负责发起并确认归集。
 */
public class AccountChainCollectionJob {

    /** 账号链工作流服务，封装归集与确认逻辑。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * 每 30 秒触发一次：创建归集候选并完成广播/确认闭环。
     */
    /**
     * Account-Chain 归集任务。
     * <p>
     * 每 30 秒（offset 17s）执行一次：遍历所有启用的 account-chain 链，
     * 执行归集流程——为每个链创建归集候选（collection_record），
     * 构建签名交易推送到 Redis 签名队列，签名完成广播后确认上链状态。
     * <p>
     * EVM 链若启用了 EIP-7702，则由 {@code Evm7702CollectionJob} 单独处理归集。
     * 注意：UTXO 链归集合并在 {@code *UtxoBatchJob} 中与提现一起处理。
     */
    @Scheduled(scheduler = "accountTaskScheduler", cron = "17/30 * * * * ?")
    public void run() {
        log.debug("AccountChain collection job begin");
        workflowService.processCollections();
        workflowService.confirmCollections();
        log.debug("AccountChain collection job end");
    }
}

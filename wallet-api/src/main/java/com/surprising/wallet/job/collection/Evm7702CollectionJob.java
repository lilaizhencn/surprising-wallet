package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.Evm7702CollectionWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EVM EIP-7702 批量归集任务。
 * <p>
 * 每 5 秒执行一次：遍历所有启用了 EIP-7702 的 EVM 链，
 * 将 collection_record 打包为批量归集交易——
 * 构建 auth 授权、调用合约 batchCollect、签名（sig1+sig2）、
 * 广播上链、确认。
 * <p>
 * 与普通 EVM 链的归集不同，7702 归集使用 EOA 委托合约方式，
 * 通过单笔 batch 交易处理多个 token 的归集，节省 Gas。
 *
 * @author atomex
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Evm7702CollectionJob {

    /** 7702 归集工作流服务。 */
    private final Evm7702CollectionWorkflowService workflowService;

    /**
     * 按固定延迟触发批量归集，处理 batchCollect 合约并完成确认。
     */
    @Scheduled(scheduler = "evm7702TaskScheduler", fixedDelayString = "${sw.wallet.evm7702.collection-delay:5000}")
    public void run() {
        log.debug("EVM 7702 collection job begin");
        workflowService.run();
        log.debug("EVM 7702 collection job end");
    }
}

package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.RbfBumpService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RBF (Replace-By-Fee) 手续费替换任务。
 *
 * <p>当提现交易因手续费过低长时间卡在 mempool 时，
 * 操作人员只需将交易 ID push 到 Redis，其余全自动。
 *
 * <h3>操作步骤（仅需一步）</h3>
 * <pre>
 *   Redis> LPUSH sw:wallet:withdraw:rbf 123
 *   （123 是 chain_signing_transaction 表的 id）
 * </pre>
 *
 * <h3>自动处理流程</h3>
 * <ol>
 *   <li>找到原始交易，提取 UTXO 列表和提现记录</li>
 *   <li>将 UTXO 标记回未花费（spent=0）</li>
 *   <li>保持 withdrawal_order 为 SIGNING，复用同一批订单和 UTXO 输入</li>
 *   <li>费率自动 ×2（若 Redis 中费率已由 FeeRateUpdater 提高则用最新值）</li>
 *   <li>用提高后的费率重建 signature JSON</li>
 *   <li>推送到首次签名队列 → sig1 → sig2 → 广播</li>
 *   <li>矿工收到新交易（相同 UTXO + 更高费 + sequence=0xFFFFFFFD）→ 替换旧交易</li>
 * </ol>
 *
 * @author lilaizhencn
 */
@Component
public class RbfBumpJob {

    /** RBF 业务服务。 */
    private final RbfBumpService bumpService;

    /** 构造 RBF 调度器。 */
    public RbfBumpJob(RbfBumpService bumpService) {
        this.bumpService = bumpService;
    }

    /**
     * 每 30 秒检查一次 RBF 触发队列，发现请求即执行重报流程。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0/30 * * * * ?")
    public void execute() {
        bumpService.process();
    }
}

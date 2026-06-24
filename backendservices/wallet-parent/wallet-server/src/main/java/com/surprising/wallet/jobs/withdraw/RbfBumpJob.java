package com.surprising.wallet.jobs.withdraw;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.WithdrawRecordService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * RBF (Replace-By-Fee) 手续费替换任务。
 *
 * <p>当提现交易因手续费过低长时间卡在 mempool 时，
 * 操作人员只需将交易 ID push 到 Redis，其余全自动。
 *
 * <h3>操作步骤（仅需一步）</h3>
 * <pre>
 *   Redis> LPUSH newex-wallet:withdraw:rbf 123
 *   （123 是 withdraw_transaction 表的 id）
 * </pre>
 *
 * <h3>自动处理流程</h3>
 * <ol>
 *   <li>找到原始交易，提取 UTXO 列表和提现记录</li>
 *   <li>将 UTXO 标记回未花费（spent=0）</li>
 *   <li>将 withdraw_record 状态重置为 WAITING</li>
 *   <li>费率自动 ×2（若 Redis 中费率已由 FeeRateUpdater 提高则用最新值）</li>
 *   <li>用提高后的费率重建 signature JSON</li>
 *   <li>推送到首次签名队列 → sig1 → sig2 → 广播</li>
 *   <li>矿工收到新交易（相同 UTXO + 更高费 + sequence=0xFFFFFFFD）→ 替换旧交易</li>
 * </ol>
 *
 * @author lilaizhencn
 */
@Slf4j
@Component
public class RbfBumpJob {

    /** RBF 触发队列 */
    public static final String WALLET_WITHDRAW_RBF_KEY = "newex-wallet:withdraw:rbf";

    /** 默认费率倍数（当操作人员忘记手动提高 Redis 费率时使用） */
    private static final double DEFAULT_FEE_BUMP_FACTOR = 2.0;

    @Autowired
    private WithdrawTransactionService txService;

    @Autowired
    private ChainJdbcRepository chainJdbcRepository;

    @Autowired
    private WithdrawRecordService recordService;

    /**
     * 每 30 秒检查一次 RBF 触发队列
     */
    @Scheduled(cron = "0/30 * * * * ?")
    public void execute() {
        Long len = REDIS.lLen(WALLET_WITHDRAW_RBF_KEY);
        if (len == 0) return;

        while (len > 0) {
            String txIdStr = REDIS.rPop(WALLET_WITHDRAW_RBF_KEY);
            if (txIdStr == null) break;

            try {
                int txId = Integer.parseInt(txIdStr.trim());
                bumpFee(txId);
            } catch (Exception e) {
                log.error("RBF bump 失败 txId={}", txIdStr, e);
            }
            len--;
        }
    }

    private void bumpFee(int txId) {
        // 1. 找到原始交易
        CurrencyEnum currency = CurrencyEnum.BTC; // BTC only for now
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        java.util.Optional<WithdrawTransaction> txOpt = txService.getById(txId, table);
        if (txOpt.isEmpty()) {
            log.error("RBF: 交易不存在 id={}", txId);
            return;
        }
        WithdrawTransaction tx = txOpt.get();

        JSONObject sigJson = JSONObject.parseObject(tx.getSignature());
        String firstSignTx = sigJson.getString("firstSignTx");
        if (firstSignTx == null || firstSignTx.isEmpty()) {
            log.error("RBF: 交易尚未完成首次签名 id={}", txId);
            return;
        }

        log.info("RBF bump 开始: txId={}, 原txid={}, 原fee={}",
                txId, tx.getTxId(), sigJson.getLongValue("fee"));

        // 2. 提取原始 UTXO
        List<UtxoTransaction> utxos = sigJson.getJSONArray("utxos")
                .toJavaList(UtxoTransaction.class);

        // 3. 统一 UTXO 运行时不再回写 legacy UTXO 表；RBF 继续复用同一组输入，
        //    并确保它们仍由当前 withdraw_transaction id 持有锁。
        for (UtxoTransaction utxo : utxos) {
            chainJdbcRepository.lockUtxo("BTC", utxo.getTxId(), utxo.getSeq(), String.valueOf(txId));
        }
        log.info("RBF: {} 个UTXO 使用统一表保持锁定", utxos.size());

        // 4. 重置 withdraw_record 状态
        List<WithdrawRecord> records = sigJson.getJSONArray("withdraw")
                .toJavaList(WithdrawRecord.class);
        for (WithdrawRecord record : records) {
            record.setStatus((byte) Constants.WAITING);
            record.setUpdateDate(Date.from(Instant.now()));
        }
        recordService.batchEdit(records, table);
        log.info("RBF: {} 条提现记录重置", records.size());

        // 5. 提高费率
        long oldFeeRate = sigJson.getLongValue("feeRate");
        long newFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());

        if (newFeeRate <= oldFeeRate) {
            // MemPool API 还没刷新，自动 x2
            newFeeRate = Math.max((long) (oldFeeRate * DEFAULT_FEE_BUMP_FACTOR), oldFeeRate + 5);
            log.warn("RBF: 费率过低，自动提高 {} sat/vB (原 {})", newFeeRate, oldFeeRate);
        }

        sigJson.put("feeRate", newFeeRate);
        tx.setSignature(sigJson.toJSONString());
        tx.setStatus(Constants.WAITING);
        tx.setTxId("rbf-" + txId);
        txService.editById(tx, table);

        // 6. 重新推送签名队列
        String val = JSONObject.toJSONString(tx);
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, val);

        log.info("RBF bump 完成: txId={}, 新费率={} sat/vB, 已推送首签队列",
                txId, newFeeRate);
    }
}

package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/**
 * RBF 手续费替换服务，负责重建并重新投递 BTC 签名交易。
 */
@Slf4j
@Service
public class RbfBumpService {
    /** RBF 触发队列。 */
    public static final String WALLET_WITHDRAW_RBF_KEY = "sw:wallet:withdraw:rbf";
    /** 默认费率倍数。 */
    private static final double DEFAULT_FEE_BUMP_FACTOR = 2.0;

    /** 签名交易仓储。 */
    private final ChainJdbcRepository repository;
    /** 链元数据服务。 */
    private final BlockchainRuntimeService blockchainRuntimeService;
    /** 提现任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** Redis 队列。 */
    private final StringRedisTemplate redis;
    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 构造 RBF 服务。 */
    public RbfBumpService(
            ChainJdbcRepository repository,
            BlockchainRuntimeService blockchainRuntimeService,
            WalletRuntimeConfigService runtimeConfigService,
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 消费 RBF 队列并重新投递手续费更高的签名交易。 */
    public void process() {
        if (!runtimeConfigService.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)) {
            log.warn("RBF bump skipped: BTC withdraw switch disabled");
            return;
        }
        Long queueSize = redis.opsForList().size(WALLET_WITHDRAW_RBF_KEY);
        if (queueSize == null || queueSize == 0) {
            return;
        }

        while (queueSize > 0) {
            String transactionId = redis.opsForList().rightPop(WALLET_WITHDRAW_RBF_KEY);
            if (transactionId == null) {
                break;
            }
            try {
                bumpFee(Integer.parseInt(transactionId.trim()));
            } catch (Exception error) {
                log.error("RBF bump 失败 txId={}", transactionId, error);
            }
            queueSize--;
        }
    }

    /** 按签名交易 ID 提高费率、恢复状态并重新投递首签队列。 */
    private void bumpFee(int transactionId) {
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata("BTC");
        String chain = currency.getName().toUpperCase(Locale.ROOT);
        java.util.Optional<WithdrawTransaction> transactionOptional =
                repository.findBitcoinLikeSigningTransactionById(currency, transactionId);
        if (transactionOptional.isEmpty()) {
            log.error("RBF: 交易不存在 id={}", transactionId);
            return;
        }
        WithdrawTransaction transaction = transactionOptional.get();

        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
        String firstSignTransaction = JacksonJson.text(signature, "firstSignTx");
        if (firstSignTransaction == null || firstSignTransaction.isEmpty()) {
            log.error("RBF: 交易尚未完成首次签名 id={}", transactionId);
            return;
        }

        log.info("RBF bump 开始: txId={}, 原txid={}, 原fee={}",
                transactionId, transaction.getTxId(), JacksonJson.longValue(signature, "fee"));

        List<UtxoTransaction> utxos = JacksonJson.toList(
                objectMapper, signature.get("utxos"), UtxoTransaction.class);
        for (UtxoTransaction utxo : utxos) {
            repository.lockUtxo(chain, utxo.getTxId(), utxo.getSeq(), String.valueOf(transactionId));
        }
        log.info("RBF: {} 个UTXO 使用统一表保持锁定", utxos.size());

        List<WithdrawRecord> records = JacksonJson.toList(
                objectMapper, signature.get("withdraw"), WithdrawRecord.class);
        for (WithdrawRecord record : records) {
            repository.updateWithdrawalStatus(
                    chain, record.getWithdrawId(), "SIGNING", null, null, null);
        }
        log.info("RBF: {} 条提现订单保持签名中", records.size());

        long oldFeeRate = JacksonJson.longValue(signature, "feeRate");
        String configuredFeeRateValue =
                redis.opsForValue().get(Constants.WALLET_FEE + currency.getIndex());
        Integer configuredFeeRate = configuredFeeRateValue == null
                ? null : Integer.valueOf(configuredFeeRateValue);
        long newFeeRate = configuredFeeRate == null ? 0L : configuredFeeRate;
        if (newFeeRate <= oldFeeRate) {
            newFeeRate = Math.max((long) (oldFeeRate * DEFAULT_FEE_BUMP_FACTOR), oldFeeRate + 5);
            log.warn("RBF: 费率过低，自动提高 {} sat/vB (原 {})", newFeeRate, oldFeeRate);
        }

        signature.put("feeRate", newFeeRate);
        transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
        transaction.setStatus(Constants.WAITING);
        transaction.setTxId("rbf-" + transactionId);
        currency.applyTo(transaction);
        repository.updateBitcoinLikeSigningTransaction(currency, transaction);

        redis.opsForList().leftPush(
                Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,
                JacksonJson.writeValue(objectMapper, transaction));
        log.info("RBF bump 完成: txId={}, 新费率={} sat/vB, 已推送首签队列",
                transactionId, newFeeRate);
    }
}

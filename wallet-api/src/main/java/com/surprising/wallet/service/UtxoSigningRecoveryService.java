package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * UTXO 签名交易恢复服务，负责重新投递长时间停留在 SIGNING 状态的交易。
 */
@Slf4j
@org.springframework.stereotype.Service
public class UtxoSigningRecoveryService {
    /** 重试阈值（秒）。 */
    private static final long STALE_SECONDS = 60;
    /** 支持恢复的 UTXO 链。 */
    private static final List<String> CHAINS = List.of("BTC", "BCH", "LTC", "DOGE");

    /** 签名交易仓储。 */
    private final ChainJdbcRepository repository;
    /** 链元数据服务。 */
    private final BlockchainRuntimeService blockchainRuntimeService;
    /** 任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** Redis 队列。 */
    private final StringRedisTemplate redis;
    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 构造签名恢复服务。 */
    public UtxoSigningRecoveryService(
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

    /** 扫描所有 UTXO 链并重新投递需要恢复的签名交易。 */
    public void recover() {
        for (String chain : CHAINS) {
            try {
                if (!runtimeConfigService.isTaskEnabled(
                        chain, WalletRuntimeConfigService.TASK_WITHDRAW)) {
                    continue;
                }
                AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(chain);
                for (WithdrawTransaction tx : repository.findStaleBitcoinLikeSigningTransactions(
                        currency, STALE_SECONDS)) {
                    if (!repository.claimBitcoinLikeSigningRecovery(
                            currency, tx.getId(), STALE_SECONDS)) {
                        continue;
                    }
                    currency.applyTo(tx);
                    redis.opsForList().leftPush(
                            Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,
                            JacksonJson.writeValue(objectMapper, tx));
                    log.info("requeued stale {} signing transaction id={}", chain, tx.getId());
                }
            } catch (Throwable error) {
                log.error("UTXO signing recovery failed for chain {}: {}",
                        chain, error.getMessage(), error);
            }
        }
    }
}

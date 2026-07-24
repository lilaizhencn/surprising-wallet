package com.surprising.wallet.job.withdraw;

import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 定时从外部 API 拉取 BTC 推荐费率并写入 Redis。
 *
 * <p>每 2 分钟从 mempool.space API 拉取当前推荐费率（sat/vB），并写入可被提现任务读取的 key。
 */
@Slf4j
@Component
public class FeeRateUpdater {

    /** mempool.space 费率 API。 */
    private static final String MEMPOOL_API = "https://mempool.space/api/v1/fees/recommended";

    /** 最低费率（sat/vB）。 */
    private static final int MIN_FEE_RATE = 2;

    /** API 异常时的兜底费率（sat/vB）。 */
    private static final int DEFAULT_FEE_RATE = 10;

    /** 全局 HTTP 客户端实例，复用连接池。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 链元数据服务。 */
    @Autowired
    private BlockchainRuntimeService blockchainRuntimeService;

    /** 运行时开关服务。 */
    @Autowired
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 每 2 分钟更新 BTC 费率。
     * 若开关关闭则跳过；更新失败则保留 Redis 现有值或写入兜底值。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0 */2 * * * ?")
    public void updateFeeRate() {
        if (!runtimeConfigService.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)) {
            log.debug("BTC fee-rate update skipped: withdraw switch disabled");
            return;
        }
        for (AssetRuntimeMetadata currency : new AssetRuntimeMetadata[]{btc()}) {
            try {
                int feeRate = fetchFeeRate();
                String key = Constants.WALLET_FEE + currency.getIndex();
                REDIS.set(key, String.valueOf(feeRate));
                log.info("费率更新: {} = {} sat/vB", key, feeRate);
            } catch (Exception e) {
                String key = Constants.WALLET_FEE + currency.getIndex();
                Integer current = REDIS.getInt(key);
                if (current == null || current <= 0) {
                    REDIS.set(key, String.valueOf(DEFAULT_FEE_RATE));
                    log.warn("费率 API 不可用，使用默认值: {} = {} sat/vB", key, DEFAULT_FEE_RATE);
                } else {
                    log.info("费率 API 不可用，保留当前值: {} = {} sat/vB", key, current);
                }
            }
        }
    }

    /**
     * 调用 mempool.space API 并解析 fastestFee，返回不低于 MIN_FEE_RATE 的费率值。
     */
    private int fetchFeeRate() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MEMPOOL_API))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }

        // API 返回: {"fastestFee":20,"halfHourFee":10,"hourFee":5,"economyFee":3,"minimumFee":2}
        String body = response.body();
        int fastestIdx = body.indexOf("\"fastestFee\":");
        if (fastestIdx < 0) {
            throw new RuntimeException("unexpected API response: " + body);
        }

        int start = body.indexOf(":", fastestIdx) + 1;
        int end = body.indexOf(",", start);
        int fastestFee = Integer.parseInt(body.substring(start, end).trim());

        int rate = Math.max(fastestFee, MIN_FEE_RATE);
        log.debug("mempool.space: fastestFee={}, selected={}", fastestFee, rate);
        return rate;
    }

    /**
     * 手动强制设置费率（运维调用），不低于下限。
     */
    public void forceUpdate(int feeRate) {
        String key = Constants.WALLET_FEE + btc().getIndex();
        REDIS.set(key, String.valueOf(Math.max(feeRate, MIN_FEE_RATE)));
        log.warn("手动强制费率: {} = {} sat/vB", key, feeRate);
    }

    /**
     * 返回 BTC 资产元数据，当前更新任务只负责 BTC 链。
     */
    private AssetRuntimeMetadata btc() {
        return blockchainRuntimeService.assetMetadata("BTC");
    }
}

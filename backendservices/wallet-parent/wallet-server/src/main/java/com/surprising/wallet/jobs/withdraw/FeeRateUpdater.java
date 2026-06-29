package com.surprising.wallet.jobs.withdraw;

import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
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
 * 定时从外部 API 拉取实时 mempool 费率并写入 Redis。
 *
 * <p>每 2 分钟从 mempool.space API 拉取当前推荐费率 (sat/vB)。
 * 取 fastestFee / economyFee 的中位数作为推荐值。
 *
 * <p>Redis key: {@code sw:wallet:withdraw:fee:currency:{index}}
 *
 * <h3>费率来源</h3>
 * <ul>
 *   <li>mempool.space (免费): GET /api/v1/fees/recommended</li>
 *   <li>备选: bitcoinfees.earn.com — 已不再维护</li>
 * </ul>
 *
 * <h3>回退逻辑</h3>
 * <p>如果 API 不可用，保留 Redis 中已有值不变，最小不低于 2 sat/vB。
 */
@Slf4j
@Component
public class FeeRateUpdater {

    /** mempool.space 费率 API */
    private static final String MEMPOOL_API = "https://mempool.space/api/v1/fees/recommended";

    /** 最低费率 (sat/vB) */
    private static final int MIN_FEE_RATE = 2;

    /** 兜底费率 (sat/vB) */
    private static final int DEFAULT_FEE_RATE = 10;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    @Autowired
    private BlockchainRuntimeService blockchainRuntimeService;

    /**
     * 每 2 分钟更新一次费率
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void updateFeeRate() {
        for (RuntimeAsset currency : new RuntimeAsset[]{btc()}) {
            try {
                int feeRate = fetchFeeRate();
                String key = Constants.WALLET_FEE + currency.getIndex();
                REDIS.set(key, String.valueOf(feeRate));
                log.info("费率更新: {} = {} sat/vB", key, feeRate);
            } catch (Exception e) {
                // API 不可用，检查 Redis 是否有值，没有则设默认
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
        int halfHourIdx = body.indexOf("\"halfHourFee\":");

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
     * 手动强制更新费率（运维接口可调用）
     */
    public void forceUpdate(int feeRate) {
        String key = Constants.WALLET_FEE + btc().getIndex();
        REDIS.set(key, String.valueOf(Math.max(feeRate, MIN_FEE_RATE)));
        log.warn("手动强制费率: {} = {} sat/vB", key, feeRate);
    }

    private RuntimeAsset btc() {
        return blockchainRuntimeService.runtimeAsset("BTC");
    }
}

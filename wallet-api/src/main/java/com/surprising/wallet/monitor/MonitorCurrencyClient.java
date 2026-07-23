package com.surprising.wallet.monitor;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.ChainScanHeightRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author atomex
 */
@Component
@Slf4j
public class MonitorCurrencyClient {

    private final ChainJdbcRepository chainJdbcRepository;

    @Value("${sw.warning.contacts}")
    private String[] contacts;

    @Value("${sw.warning.scan-stale-ms:300000}")
    private long scanStaleMs;

    public MonitorCurrencyClient(ChainJdbcRepository chainJdbcRepository) {
        this.chainJdbcRepository = chainJdbcRepository;
    }

    //    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void currencyClientMonitor() {
        log.info("检查区块更新状态开始");
        try {
            List<ChainScanHeightRecord> heightList = chainJdbcRepository.listActiveScanHeights();
            heightList.forEach((scanHeight) -> {
                if (scanHeight.getUpdatedAt() == null) {
                    return;
                }
                long lastUpdateTime = scanHeight.getUpdatedAt().toEpochMilli();
                long now = System.currentTimeMillis();
                if (lastUpdateTime + scanStaleMs < now) {
                    JSONObject params = new JSONObject();
                    params.put("chain", scanHeight.getChain());
                    params.put("scannerName", scanHeight.getScannerName());
                    params.put("bestHeight", scanHeight.getBestHeight());
                    params.put("safeHeight", scanHeight.getSafeHeight());
                    params.put("updateTime", scanHeight.getUpdatedAt().toString());
                    //给接收人发消息 可以使用钉钉机器人 或者邮件
                    for (String contact : contacts) {

                    }
                }
            });
            log.info("检查区块更新状态结束");
        } catch (Throwable e) {
            log.error("检查区块更新状态异常", e);
        }
    }
}

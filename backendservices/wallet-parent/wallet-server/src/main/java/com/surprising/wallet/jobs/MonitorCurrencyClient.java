package com.surprising.wallet.web.task;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.service.BestBlockHeightService;
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

    private final BestBlockHeightService blockHeightService;

    @Value("${atomex.warning.contacts}")
    private String[] contacts;

    public MonitorCurrencyClient(BestBlockHeightService blockHeightService) {
        this.blockHeightService = blockHeightService;
    }

    //    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void currencyClientMonitor() {
        log.info("检查区块更新状态开始");
        try {
            List<BestBlockHeight> heightList = blockHeightService.getAll();
            heightList.forEach((blockHeight) -> {
                Long interval = blockHeight.getIntervalTime();
                if (interval < 0) {
                    return;
                }
                Long lastUpdateTime = blockHeight.getUpdateDate().getTime();
                Long now = System.currentTimeMillis();
                if (lastUpdateTime + blockHeight.getIntervalTime() < now) {
                    JSONObject params = new JSONObject();
                    CurrencyEnum currency = CurrencyEnum.parseValue(blockHeight.getCurrency());
                    params.put("currency", currency.getName());
                    params.put("updateTime", blockHeight.getUpdateDate().toString());
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

package com.surprising.wallet.sig.first.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BTC 第一次签名服务。
 *
 * <p>继承 {@link AbstractBtcLikeFirstSign} 的 P2WSH 签名逻辑，
 * chain 固定为 BTC。所有 BTC 链的交易构建、手续费计算、签名生成均由基类处理。
 *
 * @author lilaizhen
 */
@Component
@Slf4j
public class BtcFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {
    /** @return 链名称 BTC */
    @Override
    public String chain() {
        return "BTC";
    }
}

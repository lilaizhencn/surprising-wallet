package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BTC 第二次签名服务。
 *
 * <p>继承 {@link AbstractBtcLikeSecondSign} 的 P2WSH 签名逻辑，
 * chain 固定为 BTC。所有 BTC 链的 witness 结构校验、签名合并均由基类处理。
 *
 * @author atomex
 */
@Component
@Slf4j
public class BtcSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {
    @Override
    public String chain() {
        return "BTC";
    }
}

package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.sig.second.ISignService;
import org.springframework.stereotype.Component;

/**
 * ETH 主币第二次签名服务。
 *
 * <p>继承 {@link AbstractEthLikeSecondSign} 的 EVM 签名逻辑，chain 固定为 ETH。
 *
 * @author atomex
 */
@Component
public class EthSecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    @Override
    public String chain() {
        return "ETH";
    }
}

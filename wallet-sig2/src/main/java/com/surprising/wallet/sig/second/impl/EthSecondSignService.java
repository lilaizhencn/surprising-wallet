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
    /**
     * 获取或查询 {@code chain} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String chain() {
        return "ETH";
    }
}

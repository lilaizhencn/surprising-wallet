package com.surprising.wallet.sig.first.service;

import org.springframework.stereotype.Component;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

/**
 * BTC 第一次签名服务。
 *
 * <p>继承 {@link AbstractBtcLikeFirstSign} 的 P2WSH 签名逻辑，
 * chain 固定为 BTC。所有 BTC 链的交易构建、手续费计算、签名生成均由基类处理。
 *
 * @author lilaizhen
 */
@Component
public class BtcFirstSignService extends AbstractBtcLikeFirstSign {
    public BtcFirstSignService(PubKeyConfig publicKeys,
                               @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys,
                               ObjectMapper mapper, @Value("${sw.wallet.network:test}") String network) {
        super(publicKeys, keys, mapper, network);
    }
    /** @return 链名称 BTC */
    @Override
    public String chain() {
        return "BTC";
    }
}

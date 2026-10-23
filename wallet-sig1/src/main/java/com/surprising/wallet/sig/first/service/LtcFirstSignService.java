package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.stereotype.Component;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Litecoin P2WSH 首签，复用 Bitcoin-like 签名逻辑并提供 Litecoin 费用策略。
 */
@Component
public class LtcFirstSignService extends AbstractBtcLikeFirstSign {
    public LtcFirstSignService(PubKeyConfig publicKeys,
                               @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys,
                               ObjectMapper mapper) {
        super(publicKeys, keys, mapper, "test");
    }
    /**
     * 获取或查询 {@code chain} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String chain() {
        return "LTC";
    }

    /**
     * 获取或查询 {@code getNetworkParameters} 对应的数据，供调用方读取当前状态。
     */
    @Override
    protected NetworkParameters getNetworkParameters() {
        return LitecoinNetworkParameters.testnet();
    }

    /**
     * 执行 {@code defaultFeeRate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    protected long defaultFeeRate() {
        return LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
    }

    /**
     * 执行 {@code dustThresholdSat} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    protected long dustThresholdSat() {
        return LitecoinFeePolicy.DUST_THRESHOLD_LITOSHI;
    }
}

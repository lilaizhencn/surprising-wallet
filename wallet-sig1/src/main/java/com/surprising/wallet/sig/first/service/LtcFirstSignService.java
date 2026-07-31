package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.stereotype.Component;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Component
@Slf4j
public class LtcFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {
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

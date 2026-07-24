package com.surprising.wallet.devfaucet.service;

import com.surprising.wallet.devfaucet.model.DevFaucetFunding;

/**
 * 开发水龙头 RPC 客户端接口，定义向测试环境地址发送测试币的标准操作。
 *
 * <p>不同链族（Bitcoin、EVM）提供各自的实现，由 {@link DevFaucetRpcClient} 的子类型决定具体发送逻辑。</p>
 *
 * @see DevFaucetFunding
 * @see com.surprising.wallet.devfaucet.service.DevFaucetBitcoinRpcClient
 * @see com.surprising.wallet.devfaucet.service.DevFaucetEvmRpcClient
 */
public interface DevFaucetRpcClient {
    /**
     * 向目标地址发送测试币。
     *
     * @param funding 充值任务
     * @return 交易哈希
     * @throws RejectedException 如果交易被拒绝（如余额不足、参数无效等永久性失败）
     * @throws AmbiguousException 如果交易结果不明确（如网络超时、RPC 异常等临时性失败）
     */
    String send(DevFaucetFunding funding);

    /** 交易被拒绝异常（永久性失败，不可重试） */
    final class RejectedException extends RuntimeException {
        RejectedException(String message) { super(message); }
        RejectedException(String message, Throwable cause) { super(message, cause); }
    }

    /** 交易结果不明确异常（临时性失败，可重试） */
    final class AmbiguousException extends RuntimeException {
        AmbiguousException(String message, Throwable cause) { super(message, cause); }
    }
}

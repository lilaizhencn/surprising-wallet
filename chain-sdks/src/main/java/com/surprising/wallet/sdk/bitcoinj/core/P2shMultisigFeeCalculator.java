package com.surprising.wallet.sdk.bitcoinj.core;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class P2shMultisigFeeCalculator {
    /**
     * 构造 {@code P2shMultisigFeeCalculator}，初始化该组件运行所需的状态和依赖。
     */
    private P2shMultisigFeeCalculator() {
    }

    /**
     * 计算或估算 {@code estimateBytes} 对应的金额、费用或资源消耗。
     */
    public static long estimateBytes(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        if (inputs <= 0 || outputs <= 0 || requiredSignatures <= 0
                || totalPubKeys < requiredSignatures || totalPubKeys > 16) {
            throw new IllegalArgumentException("invalid P2SH transaction shape");
        }
        long redeemScriptBytes = 3L + 34L * totalPubKeys;
        long redeemPushBytes = redeemScriptBytes <= 75 ? 1 : 2;
        long scriptSigBytes = 1L
                + requiredSignatures * (1L + 73L)
                + redeemPushBytes
                + redeemScriptBytes;
        long inputBytes = 32L + 4L + varIntSize(scriptSigBytes) + scriptSigBytes + 4L;
        long outputBytes = 34L;
        return 4L + varIntSize(inputs) + inputs * inputBytes
                + varIntSize(outputs) + outputs * outputBytes + 4L;
    }

    /**
     * 执行 {@code varIntSize} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static long varIntSize(long value) {
        if (value < 0xfdL) {
            return 1L;
        }
        if (value <= 0xffffL) {
            return 3L;
        }
        if (value <= 0xffffffffL) {
            return 5L;
        }
        return 9L;
    }
}

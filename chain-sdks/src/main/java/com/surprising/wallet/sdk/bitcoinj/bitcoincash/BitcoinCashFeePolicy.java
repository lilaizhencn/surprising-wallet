package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;

/**
 * Bitcoin Cash（BCH）交易费率策略，为多签交易提供费用计算和找零规划。
 *
 * <p>基于P2SH多签交易的字节估算（{@link com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator}）
 * 计算交易费用，并自动决定是否生成找零输出：</p>
 * <ul>
 *   <li>如果找零金额 >= 粉尘阈值且增加找零输出后找零仍 >= 阈值，则添加找零输出</li>
 *   <li>如果找零金额小于阈值但 >= 0，则将剩余金额全部作为手续费（不找零）</li>
 *   <li>如果输入不足以支付输出+手续费，抛出异常</li>
 * </ul>
 *
 * <p>默认费率：{@link #DEFAULT_SAT_PER_BYTE} = 1 sat/byte；默认粉尘阈值：{@link #DUST_THRESHOLD_SAT} = 546 satoshis。</p>
 */
public final class BitcoinCashFeePolicy {
    public static final long DEFAULT_SAT_PER_BYTE = 1L;
    public static final long DUST_THRESHOLD_SAT = 546L;

    private BitcoinCashFeePolicy() { }

    public static SpendPlan calculateSpendPlan(
            long inputValue,
            long recipientValue,
            int inputCount,
            int recipientCount,
            long satPerByte,
            long dustThreshold) {
        if (inputValue < 0 || recipientValue < 0 || inputCount <= 0 || recipientCount <= 0
                || satPerByte <= 0 || dustThreshold < 0) {
            throw new IllegalArgumentException("invalid BCH fee plan input");
        }
        long estimatedBytes = P2shMultisigFeeCalculator.estimateBytes(
                inputCount, recipientCount, 2, 3);
        long fee = Math.multiplyExact(estimatedBytes, satPerByte);
        long change = inputValue - recipientValue - fee;
        if (change >= dustThreshold) {
            long withChangeBytes = P2shMultisigFeeCalculator.estimateBytes(
                    inputCount, recipientCount + 1, 2, 3);
            long withChangeFee = Math.multiplyExact(withChangeBytes, satPerByte);
            long withChange = inputValue - recipientValue - withChangeFee;
            if (withChange >= dustThreshold) {
                estimatedBytes = withChangeBytes;
                fee = withChangeFee;
                change = withChange;
            } else {
                fee = inputValue - recipientValue;
                change = 0;
            }
        } else if (change >= 0) {
            fee = inputValue - recipientValue;
            change = 0;
        }
        if (change < 0) {
            throw new IllegalArgumentException("insufficient BCH input");
        }
        return new SpendPlan(fee, change, estimatedBytes);
    }

    public record SpendPlan(long fee, long change, long estimatedBytes) {
    }
}

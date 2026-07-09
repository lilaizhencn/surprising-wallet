package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;

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

package com.surprising.wallet.sdk.bitcoinj.core;

/**
 * Conservative serialized-size estimator for compressed-key legacy P2SH
 * multisig transactions.
 */
public final class P2shMultisigFeeCalculator {
    private P2shMultisigFeeCalculator() {
    }

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

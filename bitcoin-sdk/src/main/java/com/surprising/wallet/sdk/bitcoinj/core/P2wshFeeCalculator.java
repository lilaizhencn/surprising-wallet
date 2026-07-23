package com.surprising.wallet.sdk.bitcoinj.core;

public final class P2wshFeeCalculator {
    public static final long DUST_THRESHOLD_SAT = 546L;
    private static final int DEFAULT_REQUIRED_SIGNATURES = 2;
    private static final int DEFAULT_TOTAL_PUBKEYS = 3;

    private P2wshFeeCalculator() {
    }

    public static long estimateVBytes(int inputs, int outputs) {
        return estimateVBytes(inputs, outputs, DEFAULT_REQUIRED_SIGNATURES, DEFAULT_TOTAL_PUBKEYS);
    }

    public static long estimateVBytes(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        long weight = estimateWeight(inputs, outputs, requiredSignatures, totalPubKeys);
        return (weight + 3L) / 4L;
    }

    public static long estimateWeight(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        if (inputs < 1 || outputs < 1 || requiredSignatures < 1 || totalPubKeys < requiredSignatures) {
            throw new IllegalArgumentException("invalid P2WSH multisig dimensions");
        }
        long witnessScriptSize = 3L + 34L * totalPubKeys;
        long witnessPerInput = 1L + 1L + requiredSignatures * 74L + varIntSize(witnessScriptSize) + witnessScriptSize;
        long witnessBytes = 2L + inputs * witnessPerInput;
        long baseBytes = 4L + varIntSize(inputs) + inputs * 41L + varIntSize(outputs) + outputs * 43L + 4L;
        return baseBytes * 4L + witnessBytes;
    }

    public static long calculateFeeSat(int inputs, int outputs, long feeRateSatPerVByte) {
        if (feeRateSatPerVByte < 1) {
            throw new IllegalArgumentException("fee rate must be positive");
        }
        return estimateVBytes(inputs, outputs) * feeRateSatPerVByte;
    }

    public static FeeResult calculate(long inputSat, long sendSat, int inputs, int recipientOutputs,
                                      long feeRateSatPerVByte) {
        if (inputSat <= 0 || sendSat <= 0 || inputs < 1 || recipientOutputs < 1) {
            throw new IllegalArgumentException("invalid fee calculation input");
        }
        if (feeRateSatPerVByte < 1) {
            throw new IllegalArgumentException("fee rate must be positive");
        }

        int outputsWithChange = recipientOutputs + 1;
        long vbytes = estimateVBytes(inputs, outputsWithChange);
        long weight = estimateWeight(inputs, outputsWithChange, DEFAULT_REQUIRED_SIGNATURES, DEFAULT_TOTAL_PUBKEYS);
        long feeSat = vbytes * feeRateSatPerVByte;
        if (inputSat < sendSat + feeSat) {
            throw new IllegalArgumentException("fee exceeds available balance");
        }

        long changeSat = inputSat - sendSat - feeSat;
        if (changeSat > 0 && changeSat < DUST_THRESHOLD_SAT) {
            int outputsWithoutChange = recipientOutputs;
            vbytes = estimateVBytes(inputs, outputsWithoutChange);
            weight = estimateWeight(inputs, outputsWithoutChange, DEFAULT_REQUIRED_SIGNATURES, DEFAULT_TOTAL_PUBKEYS);
            feeSat = inputSat - sendSat;
            changeSat = 0;
        }
        return new FeeResult(feeSat, changeSat, vbytes, weight);
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

    public static final class FeeResult {
        private final long feeSat;
        private final long changeSat;
        private final long vbytes;
        private final long weight;

        private FeeResult(long feeSat, long changeSat, long vbytes, long weight) {
            this.feeSat = feeSat;
            this.changeSat = changeSat;
            this.vbytes = vbytes;
            this.weight = weight;
        }

        public long getFeeSat() {
            return feeSat;
        }

        public long getChangeSat() {
            return changeSat;
        }

        public long getVbytes() {
            return vbytes;
        }

        public long getWeight() {
            return weight;
        }
    }
}

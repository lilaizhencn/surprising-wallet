package com.surprising.wallet.sdk.bitcoinj.core;

/**
 * P2WSH（Pay-to-Witness-Script-Hash）多签交易费用计算器。
 * 为BIP141原生隔离见证多签地址的交易提供虚拟字节（vByte）估算和矿工费计算功能，
 * 支持可变的多签参数（requiredSignatures、totalPubKeys）和自定义费率。
 */
public final class P2wshFeeCalculator {
    /**
     * 定义 {@code DUST_THRESHOLD_SAT} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DUST_THRESHOLD_SAT = 546L;
    /**
     * 定义 {@code DEFAULT_REQUIRED_SIGNATURES} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int DEFAULT_REQUIRED_SIGNATURES = 2;
    /**
     * 定义 {@code DEFAULT_TOTAL_PUBKEYS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int DEFAULT_TOTAL_PUBKEYS = 3;

    /**
     * 构造 {@code P2wshFeeCalculator}，初始化该组件运行所需的状态和依赖。
     */
    private P2wshFeeCalculator() {
    }

    /**
     * 计算或估算 {@code estimateVBytes} 对应的金额、费用或资源消耗。
     */
    public static long estimateVBytes(int inputs, int outputs) {
        return estimateVBytes(inputs, outputs, DEFAULT_REQUIRED_SIGNATURES, DEFAULT_TOTAL_PUBKEYS);
    }

    /**
     * 计算或估算 {@code estimateVBytes} 对应的金额、费用或资源消耗。
     */
    public static long estimateVBytes(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        long weight = estimateWeight(inputs, outputs, requiredSignatures, totalPubKeys);
        return (weight + 3L) / 4L;
    }

    /**
     * 计算或估算 {@code estimateWeight} 对应的金额、费用或资源消耗。
     */
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

    /**
     * 计算或估算 {@code calculateFeeSat} 对应的金额、费用或资源消耗。
     */
    public static long calculateFeeSat(int inputs, int outputs, long feeRateSatPerVByte) {
        if (feeRateSatPerVByte < 1) {
            throw new IllegalArgumentException("fee rate must be positive");
        }
        return estimateVBytes(inputs, outputs) * feeRateSatPerVByte;
    }

    /**
     * 计算或估算 {@code calculate} 对应的金额、费用或资源消耗。
     */
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

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    public static final class FeeResult {
        /**
         * 保存 {@code feeSat}，用于保存金额、费用或链上执行状态。
         */
        private final long feeSat;
        /**
         * 保存 {@code changeSat}，用于承载当前对象的运行配置或业务数据。
         */
        private final long changeSat;
        /**
         * 保存 {@code vbytes}，用于保存业务集合或索引状态。
         */
        private final long vbytes;
        /**
         * 保存 {@code weight}，用于承载当前对象的运行配置或业务数据。
         */
        private final long weight;

        /**
         * 构造 {@code FeeResult}，初始化该组件运行所需的状态和依赖。
         */
        private FeeResult(long feeSat, long changeSat, long vbytes, long weight) {
            this.feeSat = feeSat;
            this.changeSat = changeSat;
            this.vbytes = vbytes;
            this.weight = weight;
        }

        /**
         * 获取或查询 {@code getFeeSat} 对应的数据，供调用方读取当前状态。
         */
        public long getFeeSat() {
            return feeSat;
        }

        /**
         * 获取或查询 {@code getChangeSat} 对应的数据，供调用方读取当前状态。
         */
        public long getChangeSat() {
            return changeSat;
        }

        /**
         * 获取或查询 {@code getVbytes} 对应的数据，供调用方读取当前状态。
         */
        public long getVbytes() {
            return vbytes;
        }

        /**
         * 获取或查询 {@code getWeight} 对应的数据，供调用方读取当前状态。
         */
        public long getWeight() {
            return weight;
        }
    }
}

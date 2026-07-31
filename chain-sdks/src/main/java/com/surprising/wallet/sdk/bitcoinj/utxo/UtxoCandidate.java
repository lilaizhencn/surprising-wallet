package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Objects;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class UtxoCandidate implements Comparable<UtxoCandidate> {
    /**
     * 保存 {@code txId}，用于标识交易、区块或业务记录。
     */
    private final String txId;
    /**
     * 保存 {@code index}，用于承载当前对象的运行配置或业务数据。
     */
    private final int index;
    /**
     * 保存 {@code satoshis}，用于承载当前对象的运行配置或业务数据。
     */
    private final long satoshis;

    /**
     * 构造 {@code UtxoCandidate}，初始化该组件运行所需的状态和依赖。
     */
    public UtxoCandidate(String txId, int index, long satoshis) {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId must not be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (satoshis <= 0) {
            throw new IllegalArgumentException("satoshis must be positive");
        }
        this.txId = txId;
        this.index = index;
        this.satoshis = satoshis;
    }

    /**
     * 获取或查询 {@code getTxId} 对应的数据，供调用方读取当前状态。
     */
    public String getTxId() {
        return txId;
    }

    /**
     * 获取或查询 {@code getIndex} 对应的数据，供调用方读取当前状态。
     */
    public int getIndex() {
        return index;
    }

    /**
     * 获取或查询 {@code getSatoshis} 对应的数据，供调用方读取当前状态。
     */
    public long getSatoshis() {
        return satoshis;
    }

    /**
     * 执行 {@code compareTo} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public int compareTo(UtxoCandidate other) {
        int byValue = Long.compare(this.satoshis, other.satoshis);
        if (byValue != 0) {
            return byValue;
        }
        int byTx = this.txId.compareTo(other.txId);
        if (byTx != 0) {
            return byTx;
        }
        return Integer.compare(this.index, other.index);
    }

    /**
     * 比较当前对象与目标对象是否具有相同的业务含义。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UtxoCandidate other)) {
            return false;
        }
        return index == other.index && satoshis == other.satoshis && txId.equals(other.txId);
    }

    /**
     * 根据对象的业务字段计算哈希值，保证与相等性判断一致。
     */
    @Override
    public int hashCode() {
        return Objects.hash(txId, index, satoshis);
    }

    /**
     * 将对象转换为便于日志记录和排障的字符串表示。
     */
    @Override
    public String toString() {
        return txId + ":" + index + "@" + satoshis;
    }
}

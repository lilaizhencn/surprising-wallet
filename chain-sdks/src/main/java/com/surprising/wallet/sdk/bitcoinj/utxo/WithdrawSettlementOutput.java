package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Objects;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class WithdrawSettlementOutput {
    /**
     * 保存 {@code userId}，用于标识交易、区块或业务记录。
     */
    private final long userId;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private final String address;
    /**
     * 保存 {@code satoshis}，用于承载当前对象的运行配置或业务数据。
     */
    private final long satoshis;

    /**
     * 构造 {@code WithdrawSettlementOutput}，初始化该组件运行所需的状态和依赖。
     */
    public WithdrawSettlementOutput(long userId, String address, long satoshis) {
        if (userId < 0) {
            throw new IllegalArgumentException("userId must be non-negative");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        if (satoshis <= 0) {
            throw new IllegalArgumentException("satoshis must be positive");
        }
        this.userId = userId;
        this.address = address;
        this.satoshis = satoshis;
    }

    /**
     * 获取或查询 {@code getUserId} 对应的数据，供调用方读取当前状态。
     */
    public long getUserId() {
        return userId;
    }

    /**
     * 获取或查询 {@code getAddress} 对应的数据，供调用方读取当前状态。
     */
    public String getAddress() {
        return address;
    }

    /**
     * 获取或查询 {@code getSatoshis} 对应的数据，供调用方读取当前状态。
     */
    public long getSatoshis() {
        return satoshis;
    }

    /**
     * 比较当前对象与目标对象是否具有相同的业务含义。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WithdrawSettlementOutput other)) {
            return false;
        }
        return userId == other.userId && satoshis == other.satoshis && address.equals(other.address);
    }

    /**
     * 根据对象的业务字段计算哈希值，保证与相等性判断一致。
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId, address, satoshis);
    }
}

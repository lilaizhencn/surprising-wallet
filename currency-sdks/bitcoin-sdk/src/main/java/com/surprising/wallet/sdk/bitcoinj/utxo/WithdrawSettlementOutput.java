package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Objects;

/**
 * Per-user settlement output used for merged BTC withdrawals.
 */
public final class WithdrawSettlementOutput {
    private final long userId;
    private final String address;
    private final long satoshis;

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

    public long getUserId() {
        return userId;
    }

    public String getAddress() {
        return address;
    }

    public long getSatoshis() {
        return satoshis;
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(userId, address, satoshis);
    }
}

package com.surprising.wallet.chain.evm;

import org.web3j.crypto.Keys;

import java.math.BigInteger;
import java.util.Locale;

/** Exact on-chain payout item. Native currency is represented by the zero address. */
public record Evm7702PayoutItem(
        byte[] withdrawalId,
        BigInteger itemIndex,
        String token,
        String recipient,
        BigInteger amount,
        BigInteger callGasLimit
) {
    public Evm7702PayoutItem {
        if (withdrawalId == null || withdrawalId.length != 32 || allZero(withdrawalId)) {
            throw new IllegalArgumentException("withdrawalId must contain a non-zero bytes32 value");
        }
        withdrawalId = withdrawalId.clone();
        requireUint(itemIndex, "itemIndex", true);
        token = requireAddress(token, "token", true);
        recipient = requireAddress(recipient, "recipient", false);
        requireUint(amount, "amount", false);
        requireUint(callGasLimit, "callGasLimit", false);
    }

    /**
     * 处理 {@code withdrawalId} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    @Override
    public byte[] withdrawalId() {
        return withdrawalId.clone();
    }
    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    static String requireAddress(String value, String field, boolean allowZero) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^0x[0-9a-f]{40}$")
                || (!allowZero && normalized.equals("0x0000000000000000000000000000000000000000"))) {
            throw new IllegalArgumentException(field + " must be a valid EVM address");
        }
        return Keys.toChecksumAddress(normalized);
    }
    /**
     * 校验 {@code requireUint} 对应的前置条件，不满足时抛出明确异常。
     */
    static void requireUint(BigInteger value, String field, boolean allowZero) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)
                || value.bitLength() > 256) {
            throw new IllegalArgumentException(field + " must be a valid uint256");
        }
    }
    /**
     * 执行 {@code allZero} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static boolean allZero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
}

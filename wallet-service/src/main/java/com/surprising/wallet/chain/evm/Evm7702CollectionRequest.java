package com.surprising.wallet.chain.evm;

import org.web3j.crypto.Keys;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;

/** Exact on-chain CollectionRequest representation. All amounts are atomic integers. */
public record Evm7702CollectionRequest(
        byte[] batchId,
        BigInteger itemIndex,
        String authority,
        String collector,
        String token,
        String recipient,
        BigInteger amount,
        BigInteger operationNonce,
        BigInteger deadline,
        BigInteger callGasLimit
) {
    public Evm7702CollectionRequest {
        if (batchId == null || batchId.length != 32) {
            throw new IllegalArgumentException("batchId must contain exactly 32 bytes");
        }
        batchId = batchId.clone();
        requireUint(itemIndex, "itemIndex", true);
        authority = requireAddress(authority, "authority", false);
        collector = requireAddress(collector, "collector", false);
        token = requireAddress(token, "token", true);
        recipient = requireAddress(recipient, "recipient", false);
        requireUint(amount, "amount", false);
        requireUint(operationNonce, "operationNonce", true);
        requireUint(deadline, "deadline", false);
        requireUint(callGasLimit, "callGasLimit", false);
    }

    /**
     * 执行 {@code batchId} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Override
    public byte[] batchId() {
        return batchId.clone();
    }
    /**
     * 校验 {@code requireNotExpired} 对应的前置条件，不满足时抛出明确异常。
     */
    public void requireNotExpired(Instant now) {
        if (deadline.compareTo(BigInteger.valueOf(now.getEpochSecond())) <= 0) {
            throw new IllegalArgumentException("collection signature deadline has expired");
        }
    }
    /**
     * 校验 {@code requireUint} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireUint(BigInteger value, String field, boolean allowZero) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)
                || value.bitLength() > 256) {
            throw new IllegalArgumentException(field + " must be a valid uint256");
        }
    }
    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    private static String requireAddress(String value, String field, boolean allowZero) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^0x[0-9a-f]{40}$")
                || (!allowZero && normalized.equals("0x0000000000000000000000000000000000000000"))) {
            throw new IllegalArgumentException(field + " must be a valid EVM address");
        }
        return Keys.toChecksumAddress(normalized);
    }
}

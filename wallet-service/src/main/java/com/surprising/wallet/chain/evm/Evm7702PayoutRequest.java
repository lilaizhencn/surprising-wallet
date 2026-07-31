package com.surprising.wallet.chain.evm;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One tenant hot-wallet authorization covering a complete payout batch. */
public record Evm7702PayoutRequest(
        byte[] batchId,
        String authority,
        String executor,
        List<Evm7702PayoutItem> items,
        BigInteger operationNonce,
        BigInteger deadline
) {
    public Evm7702PayoutRequest {
        if (batchId == null || batchId.length != 32 || allZero(batchId)) {
            throw new IllegalArgumentException("batchId must contain a non-zero bytes32 value");
        }
        batchId = batchId.clone();
        authority = Evm7702PayoutItem.requireAddress(authority, "authority", false);
        executor = Evm7702PayoutItem.requireAddress(executor, "executor", false);
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("payout batch must contain 1..100 items");
        }
        items = List.copyOf(items);
        /** 记录本批次已出现的提现 ID，防止同一提现被重复授权。 */
        Set<String> withdrawalIds = new HashSet<>();
        /** 当前批次项的连续序号，用于校验 itemIndex 从零开始且不跳号。 */
        int index = 0;
        for (; index < items.size(); index++) {
            // 当前序号对应的提现授权项，用于校验序号和提现 ID 的唯一性。
            Evm7702PayoutItem item = items.get(index);
            if (!item.itemIndex().equals(BigInteger.valueOf(index))) {
                throw new IllegalArgumentException("payout item indexes must be contiguous from zero");
            }
            if (!withdrawalIds.add(org.web3j.utils.Numeric.toHexString(item.withdrawalId()))) {
                throw new IllegalArgumentException("withdrawalId must be unique within a payout batch");
            }
        }
        Evm7702PayoutItem.requireUint(operationNonce, "operationNonce", true);
        Evm7702PayoutItem.requireUint(deadline, "deadline", false);
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
            throw new IllegalArgumentException("payout signature deadline has expired");
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

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
        Set<String> withdrawalIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
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

    @Override
    public byte[] batchId() {
        return batchId.clone();
    }
    public void requireNotExpired(Instant now) {
        if (deadline.compareTo(BigInteger.valueOf(now.getEpochSecond())) <= 0) {
            throw new IllegalArgumentException("payout signature deadline has expired");
        }
    }
    private static boolean allZero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
}

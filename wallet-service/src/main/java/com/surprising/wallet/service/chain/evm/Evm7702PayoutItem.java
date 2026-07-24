package com.surprising.wallet.service.chain.evm;

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

    @Override
    public byte[] withdrawalId() {
        return withdrawalId.clone();
    }
    static String requireAddress(String value, String field, boolean allowZero) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^0x[0-9a-f]{40}$")
                || (!allowZero && normalized.equals("0x0000000000000000000000000000000000000000"))) {
            throw new IllegalArgumentException(field + " must be a valid EVM address");
        }
        return Keys.toChecksumAddress(normalized);
    }
    static void requireUint(BigInteger value, String field, boolean allowZero) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)
                || value.bitLength() > 256) {
            throw new IllegalArgumentException(field + " must be a valid uint256");
        }
    }
    private static boolean allZero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
}

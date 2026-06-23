package com.surprising.wallet.common.key;

import java.util.Arrays;

/**
 * SLIP-0010 Ed25519 derivation paths. The zero user index exactly matches the
 * documented chain base path; non-zero users replace the account component.
 */
public enum Ed25519Chain {
    SOLANA(501, 4),
    TON(607, 4),
    APTOS(637, 5),
    SUI(784, 5);

    private final int coinType;
    private final int depth;

    Ed25519Chain(int coinType, int depth) {
        this.coinType = coinType;
        this.depth = depth;
    }

    public int coinType() {
        return coinType;
    }

    public int[] pathForUser(long userIndex) {
        if (userIndex < 0 || userIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("user index must be between 0 and 2147483647");
        }
        int[] path = depth == 4
                ? new int[]{44, coinType, (int) userIndex, 0}
                : new int[]{44, coinType, (int) userIndex, 0, 0};
        return Arrays.copyOf(path, path.length);
    }

    public String pathString(long userIndex) {
        return switch (depth) {
            case 4 -> "m/44'/" + coinType + "'/" + userIndex + "'/0'";
            case 5 -> "m/44'/" + coinType + "'/" + userIndex + "'/0'/0'";
            default -> throw new IllegalStateException("unsupported path depth " + depth);
        };
    }
}

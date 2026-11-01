package com.surprising.wallet.chain.model;

/** Public native block identity; height alone cannot resume a slot-based chain protocol. */
public record ChainScanPoint(long height, long slot, String hash) {
    public ChainScanPoint {
        if (height < 0 || slot < 0 || hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("native scan point requires height, slot and 32-byte hash");
    }
}

package com.surprising.wallet.common.key;

import java.util.Arrays;

public final class Ed25519DerivedKey {
    private final String derivationPath;
    private final byte[] privateSeed;
    private final byte[] publicKey;

    Ed25519DerivedKey(String derivationPath, byte[] privateSeed, byte[] publicKey) {
        this.derivationPath = derivationPath;
        this.privateSeed = Arrays.copyOf(privateSeed, privateSeed.length);
        this.publicKey = Arrays.copyOf(publicKey, publicKey.length);
    }

    public String derivationPath() {
        return derivationPath;
    }

    public byte[] privateSeed() {
        return Arrays.copyOf(privateSeed, privateSeed.length);
    }

    public byte[] publicKey() {
        return Arrays.copyOf(publicKey, publicKey.length);
    }
}

package com.surprising.wallet.common.key;

import java.util.Arrays;

/**
 * Ed25519 派生密钥结果，封装派生路径、私钥种子和公钥。
 *
 * <p>由 {@link Ed25519KeyProvider#derive} 生成，所有字节数组字段
 * 均做防御性拷贝，避免外部修改影响内部状态。</p>
 */
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

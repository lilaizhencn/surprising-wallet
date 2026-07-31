package com.surprising.wallet.sdk.ed25519;

import java.util.Arrays;

/**
 * Ed25519 派生密钥结果，封装派生路径、私钥种子和公钥。
 *
 * <p>由 {@link Ed25519KeyProvider#derive} 生成，所有字节数组字段
 * 均做防御性拷贝，避免外部修改影响内部状态。</p>
 */
public final class Ed25519DerivedKey {
    /**
     * 保存 {@code derivationPath}，用于承载当前对象的运行配置或业务数据。
     */
    private final String derivationPath;
    /**
     * 保存 {@code privateSeed}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final byte[] privateSeed;
    /**
     * 保存 {@code publicKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final byte[] publicKey;

    /**
     * 构造 {@code Ed25519DerivedKey}，初始化该组件运行所需的状态和依赖。
     */
    Ed25519DerivedKey(String derivationPath, byte[] privateSeed, byte[] publicKey) {
        this.derivationPath = derivationPath;
        this.privateSeed = Arrays.copyOf(privateSeed, privateSeed.length);
        this.publicKey = Arrays.copyOf(publicKey, publicKey.length);
    }

    /**
     * 执行 {@code derivationPath} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String derivationPath() {
        return derivationPath;
    }

    /**
     * 执行 {@code privateSeed} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public byte[] privateSeed() {
        return Arrays.copyOf(privateSeed, privateSeed.length);
    }

    /**
     * 执行 {@code publicKey} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public byte[] publicKey() {
        return Arrays.copyOf(publicKey, publicKey.length);
    }
}

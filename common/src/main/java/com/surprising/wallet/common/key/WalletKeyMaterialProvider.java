package com.surprising.wallet.common.key;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;

/**
 * 钱包密钥材料提供者，负责校验配置并提供各类密钥材料（BIP32 根密钥、Ed25519 密钥等）。
 *
 * <p>支持三种运行模式，通过 {@link Mode} 枚举控制不同角色可访问的密钥类型：</p>
 * <ul>
 *   <li>{@link Mode#WALLET_SERVER} - 钱包服务端，可访问 sig2 私钥和 Ed25519 密钥</li>
 *   <li>{@link Mode#SIG1} - 签名方 1，仅可访问 sig1 私钥</li>
 *   <li>{@link Mode#SIG2} - 签名方 2，仅可访问 sig2 私钥</li>
 * </ul>
 *
 * @see WalletKeyConfig
 */
public final class WalletKeyMaterialProvider {
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    public enum Mode {
        /**
         * 定义 {@code WALLET_SERVER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        WALLET_SERVER,
        /**
         * 定义 {@code SIG1} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        SIG1,
        /**
         * 定义 {@code SIG2} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        SIG2
    }

    /**
     * 保存 {@code mode}，用于承载当前对象的运行配置或业务数据。
     */
    private final Mode mode;
    /**
     * 保存 {@code material}，用于保存签名、认证或密钥相关材料。
     */
    private final Material material;

    /**
     * 构造 {@code WalletKeyMaterialProvider}，初始化该组件运行所需的状态和依赖。
     */
    public WalletKeyMaterialProvider(WalletKeyConfig config, Mode mode) {
        WalletSeedCodec.validate(config);
        this.mode = mode;
        Bip32Node sig1 = WalletSeedCodec.bip32Root("sig1Seed", config.sig1Seed());
        Bip32Node sig2 = WalletSeedCodec.bip32Root("sig2Seed", config.sig2Seed());
        Bip32Node recovery = WalletSeedCodec.bip32Root("recoverySeed", config.recoverySeed());
        this.material = new Material(
                mode == Mode.SIG1 ? sig1 : null,
                mode == Mode.SIG2 || mode == Mode.WALLET_SERVER ? sig2 : null,
                publicOnly(sig1),
                publicOnly(sig2),
                publicOnly(recovery),
                mode == Mode.WALLET_SERVER
                        ? new Ed25519KeyProvider(WalletSeedCodec.decode(
                                "ed25519Seed", config.ed25519Seed()))
                        : null);
    }

    /**
     * 判断 {@code isConfigured} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isConfigured() {
        return true;
    }

    /**
     * 执行 {@code sig1Root} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Bip32Node sig1Root() {
        return requirePrivate(material.sig1Root, "sig1");
    }

    /**
     * 执行 {@code sig2Root} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Bip32Node sig2Root() {
        return requirePrivate(material.sig2Root, "sig2");
    }

    /**
     * 执行 {@code sig1PublicRoot} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Bip32Node sig1PublicRoot() {
        return material.sig1PublicRoot;
    }

    /**
     * 执行 {@code sig2PublicRoot} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Bip32Node sig2PublicRoot() {
        return material.sig2PublicRoot;
    }

    /**
     * 执行 {@code recoveryPublicRoot} 对应的签名或签名恢复，保证交易数据可验证。
     */
    public Bip32Node recoveryPublicRoot() {
        return material.recoveryPublicRoot;
    }

    /**
     * 执行 {@code ed25519} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Ed25519KeyProvider ed25519() {
        Ed25519KeyProvider provider = material.ed25519;
        if (provider == null) {
            throw new IllegalStateException("Ed25519 key material is not available in " + mode + " process");
        }
        return provider;
    }

    /**
     * 执行 {@code publicOnly} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Bip32Node publicOnly(Bip32Node root) {
        return Bip32Node.decode(root.pubSerialize(Bip32Node.TYPE_BITCOIN, false));
    }

    /**
     * 校验 {@code requirePrivate} 对应的前置条件，不满足时抛出明确异常。
     */
    private Bip32Node requirePrivate(Bip32Node root, String name) {
        if (root == null) {
            throw new IllegalStateException(name + " private key material is not available in " + mode + " process");
        }
        return root;
    }

    private record Material(Bip32Node sig1Root, Bip32Node sig2Root,
                            Bip32Node sig1PublicRoot, Bip32Node sig2PublicRoot,
                            Bip32Node recoveryPublicRoot, Ed25519KeyProvider ed25519) {
    }
}

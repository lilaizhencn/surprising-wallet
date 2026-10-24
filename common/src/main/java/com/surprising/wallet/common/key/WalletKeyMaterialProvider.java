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
    public enum Mode {
        WALLET_SERVER,
        SIG1,
        SIG2
    }

    private final Mode mode;
    /**
     * 保存 {@code material}，用于保存签名、认证或密钥相关材料。
     */
    private final Material material;

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

    private WalletKeyMaterialProvider(Mode mode, Material material) {
        this.mode = mode;
        this.material = material;
    }

    public static WalletKeyMaterialProvider forSig1(String seed, String sig2Public, String recoveryPublic) {
        Bip32Node root = WalletSeedCodec.bip32Root("sig1Seed", seed);
        Bip32Node other = extendedPublic(sig2Public);
        Bip32Node recovery = extendedPublic(recoveryPublic);
        requireDistinct(publicOnly(root), other, recovery);
        return new WalletKeyMaterialProvider(Mode.SIG1,
                new Material(root, null, publicOnly(root), other, recovery, null));
    }

    public static WalletKeyMaterialProvider forSig2(String seed) {
        Bip32Node root = WalletSeedCodec.bip32Root("sig2Seed", seed);
        return new WalletKeyMaterialProvider(Mode.SIG2,
                new Material(null, root, null, publicOnly(root), null, null));
    }

    public static WalletKeyMaterialProvider forApi(String sig2Seed, String edSeed, String sig1Public, String recoveryPublic) {
        Bip32Node root = WalletSeedCodec.bip32Root("sig2Seed", sig2Seed);
        Bip32Node first = extendedPublic(sig1Public);
        Bip32Node recovery = extendedPublic(recoveryPublic);
        requireDistinct(first, publicOnly(root), recovery,
                publicOnly(WalletSeedCodec.bip32Root("ed25519Seed", edSeed)));
        return new WalletKeyMaterialProvider(Mode.WALLET_SERVER,
                new Material(null, root, first, publicOnly(root), recovery,
                        new Ed25519KeyProvider(WalletSeedCodec.decode("ed25519Seed", edSeed))));
    }

    private static Bip32Node extendedPublic(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("extended public key is required");
        Bip32Node node = Bip32Node.decode(value);
        if (node.getEcKey().hasPrivKey()) throw new IllegalArgumentException("expected extended public key, not private key");
        return node;
    }

    private static void requireDistinct(Bip32Node... nodes) {
        var keys = new java.util.HashSet<String>();
        for (Bip32Node node : nodes) {
            if (!keys.add(java.util.HexFormat.of().formatHex(node.getEcKey().getPubKey())))
                throw new IllegalArgumentException("multisig public keys must differ");
        }
    }

    public boolean hasApiKeyMaterial() {
        return material.sig2Root != null && material.sig2Root.getEcKey().hasPrivKey()
                && material.ed25519 != null && material.sig1PublicRoot != null
                && material.recoveryPublicRoot != null;
    }

    public Bip32Node sig1Root() {
        return requirePrivate(material.sig1Root, "sig1");
    }

    public Bip32Node sig2Root() {
        return requirePrivate(material.sig2Root, "sig2");
    }

    public Bip32Node sig1PublicRoot() {
        return material.sig1PublicRoot;
    }

    public Bip32Node sig2PublicRoot() {
        return material.sig2PublicRoot;
    }

    /**
     * 执行 {@code recoveryPublicRoot} 对应的签名或签名恢复，保证交易数据可验证。
     */
    public Bip32Node recoveryPublicRoot() {
        return material.recoveryPublicRoot;
    }

    public Ed25519KeyProvider ed25519() {
        Ed25519KeyProvider provider = material.ed25519;
        if (provider == null) {
            throw new IllegalStateException("Ed25519 key material is not available in " + mode + " process");
        }
        return provider;
    }

    private static Bip32Node publicOnly(Bip32Node root) {
        return Bip32Node.decode(root.pubSerialize(Bip32Node.TYPE_BITCOIN, false));
    }

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

package com.surprising.wallet.common.key;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;

/**
 * 钱包密钥材料提供者，负责从配置存储中加载、缓存并提供各类密钥材料（BIP32 根密钥、Ed25519 密钥等）。
 *
 * <p>支持三种运行模式，通过 {@link Mode} 枚举控制不同角色可访问的密钥类型：</p>
 * <ul>
 *   <li>{@link Mode#WALLET_SERVER} - 钱包服务端，可访问 sig2 私钥和 Ed25519 密钥</li>
 *   <li>{@link Mode#SIG1} - 签名方 1，仅可访问 sig1 私钥</li>
 *   <li>{@link Mode#SIG2} - 签名方 2，仅可访问 sig2 私钥</li>
 * </ul>
 *
 * <p>密钥材料采用延迟加载（lazy loading）和双重检查锁定（double-checked locking）的方式，
 * 确保线程安全。调用 {@link #reload()} 可清空缓存，强制下次访问时重新加载。</p>
 *
 * @see WalletKeyConfig
 * @see WalletKeyConfigStore
 */
public final class WalletKeyMaterialProvider {
    public enum Mode {
        WALLET_SERVER,
        SIG1,
        SIG2
    }

    private final WalletKeyConfigStore store;
    private final Mode mode;
    private volatile Material material;

    public WalletKeyMaterialProvider(WalletKeyConfigStore store, Mode mode) {
        this.store = store;
        this.mode = mode;
    }

    public boolean isConfigured() {
        return store.find().isPresent();
    }

    public Bip32Node sig1Root() {
        return requirePrivate(material().sig1Root, "sig1");
    }

    public Bip32Node sig2Root() {
        return requirePrivate(material().sig2Root, "sig2");
    }

    public Bip32Node sig1PublicRoot() {
        return material().sig1PublicRoot;
    }

    public Bip32Node sig2PublicRoot() {
        return material().sig2PublicRoot;
    }

    public Bip32Node recoveryPublicRoot() {
        return material().recoveryPublicRoot;
    }

    public Ed25519KeyProvider ed25519() {
        Ed25519KeyProvider provider = material().ed25519;
        if (provider == null) {
            throw new IllegalStateException("Ed25519 key material is not available in " + mode + " process");
        }
        return provider;
    }

    public void reload() {
        material = null;
    }

    private Material material() {
        Material result = material;
        if (result == null) {
            synchronized (this) {
                result = material;
                if (result == null) {
                    WalletKeyConfig config = store.require();
                    WalletSeedCodec.validate(config);
                    Bip32Node sig1 = WalletSeedCodec.bip32Root("sig1Seed", config.sig1Seed());
                    Bip32Node sig2 = WalletSeedCodec.bip32Root("sig2Seed", config.sig2Seed());
                    Bip32Node recovery = WalletSeedCodec.bip32Root("recoverySeed", config.recoverySeed());
                    result = new Material(
                            mode == Mode.SIG1 ? sig1 : null,
                            mode == Mode.SIG2 || mode == Mode.WALLET_SERVER ? sig2 : null,
                            publicOnly(sig1),
                            publicOnly(sig2),
                            publicOnly(recovery),
                            mode == Mode.WALLET_SERVER
                                    ? new Ed25519KeyProvider(WalletSeedCodec.decode(
                                            "ed25519Seed", config.ed25519Seed()))
                                    : null);
                    material = result;
                }
            }
        }
        return result;
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

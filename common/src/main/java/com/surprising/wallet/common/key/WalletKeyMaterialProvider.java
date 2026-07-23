package com.surprising.wallet.common.key;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;

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

package com.surprising.wallet.common.key;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 钱包种子编解码工具类。
 *
 * <p>提供 Base64 种子解码（固定 32 字节）、BIP-32 根节点生成、
 * 以及 {@link WalletKeyConfig} 四种子去重与校验。</p>
 */
public final class WalletSeedCodec {
    /**
     * 定义 {@code SEED_BYTES} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final int SEED_BYTES = 32;

    /**
     * 构造 {@code WalletSeedCodec}，初始化该组件运行所需的状态和依赖。
     */
    private WalletSeedCodec() {
    }

    /**
     * 解析或转换 {@code decode} 对应的数据，并校验其格式和边界。
     */
    public static byte[] decode(String name, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        final byte[] seed;
        try {
            seed = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be Base64", error);
        }
        if (seed.length != SEED_BYTES) {
            throw new IllegalArgumentException(name + " must decode to exactly 32 bytes");
        }
        return seed;
    }

    /**
     * 执行 {@code bip32Root} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static Bip32Node bip32Root(String name, String encoded) {
        return Bip32Node.getMasterKey(decode(name, encoded));
    }

    /**
     * 校验 {@code validate} 对应的前置条件，不满足时抛出明确异常。
     */
    public static void validate(WalletKeyConfig config) {
        List<byte[]> seeds = List.of(
                decode("sig1Seed", config.sig1Seed()),
                decode("sig2Seed", config.sig2Seed()),
                decode("recoverySeed", config.recoverySeed()),
                decode("ed25519Seed", config.ed25519Seed()));
        Set<String> unique = new HashSet<>();
        for (byte[] seed : seeds) {
            if (!unique.add(Base64.getEncoder().encodeToString(seed))) {
                throw new IllegalArgumentException("the four seeds must be different");
            }
        }
        bip32Root("sig1Seed", config.sig1Seed());
        bip32Root("sig2Seed", config.sig2Seed());
        bip32Root("recoverySeed", config.recoverySeed());
        new Ed25519KeyProvider(seeds.get(3));
    }
}

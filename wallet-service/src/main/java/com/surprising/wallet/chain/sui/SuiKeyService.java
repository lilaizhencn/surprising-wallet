package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sui 链密钥服务，负责 Ed25519 密钥派生、签名和地址生成。
 *
 * <p>Sui 地址由公钥前拼接 0x00 scheme 字节，再计算 Blake2b256 哈希生成。
 * 与 Aptos 不同，Sui 使用 Blake2b 而非 SHA3-256 作为地址哈希算法。</p>
 *
 * <p>支持生产环境和测试环境两种密钥来源。</p>
 *
 * @see AptosKeyService
 */
@Component
public
class SuiKeyService {

    /** Ed25519 单签方案标识字节 */
    private static final byte ED25519_SCHEME = 0x00;

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者（非 null 时使用） */
    private final Ed25519KeyProvider testProvider;

    /**
     * 生产环境构造器。
     */
    @Autowired
    public SuiKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public SuiKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.SUI, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.SUI, biz, userId, derivationIndex);
    }
    public String address(long derivationIndex) {
        return address(derive(derivationIndex).publicKey());
    }
    public byte[] sign(long derivationIndex, byte[] message) {
        return provider().sign(Ed25519Chain.SUI, derivationIndex, message);
    }
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return sign(derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.SUI, biz, userId, derivationIndex, message);
    }
    /**
     * 从 Ed25519 公钥计算 Sui 地址。
     *
     * <p>计算公式：Blake2b256(0x00 || publicKey)，输出为 0x 前缀的十六进制字符串。</p>
     *
     * @param publicKey Ed25519 公钥（32 字节）
     * @return Sui 地址
     */
    public static String address(byte[] publicKey) {
        byte[] data = new byte[1 + publicKey.length];
        data[0] = ED25519_SCHEME;
        System.arraycopy(publicKey, 0, data, 1, publicKey.length);
        return SuiHex.withPrefix(new Blake2b.Blake2b256().digest(data));
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

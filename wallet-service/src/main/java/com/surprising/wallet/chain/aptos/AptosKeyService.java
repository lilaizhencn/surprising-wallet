package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Aptos 链密钥服务，负责 Ed25519 密钥派生、签名和地址生成。
 *
 * <p>Aptos 地址由公钥的 SHA3-256 哈希加上 Ed25519 scheme 字节（0x00）生成。
 * 支持生产环境密钥（通过 {@link WalletKeyMaterialProvider}）和测试环境密钥（直接使用 master seed）。</p>
 *
 * <p>地址生成公式：<code>SHA3-256(publicKey || 0x00)</code></p>
 */
@Component
public class AptosKeyService {

    /** Ed25519 单签方案标识字节 */
    private static final byte APTOS_ED25519_SCHEME = 0x00;

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者（非 null 时使用） */
    private final Ed25519KeyProvider testProvider;

    /**
     * 生产环境构造器。
     *
     * @param keyMaterial {@link WalletKeyMaterialProvider} 密钥材料提供者
     */
    @Autowired
    public AptosKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }

    /**
     * 测试环境构造器，直接使用编码后的 master seed。
     *
     * @param encodedMasterSeed Base64 编码的 master seed
     */
    public AptosKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }

    /**
     * 检查密钥服务是否已配置。
     *
     * @return true 如果已配置可用
     */
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }

    /**
     * 派生 Ed25519 密钥（简化模式，userId=0, biz=0）。
     *
     * @param derivationIndex 派生索引
     * @return 派生密钥
     */
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.APTOS, derivationIndex);
    }

    /**
     * 派生 Ed25519 密钥（完整模式）。
     *
     * @param userId          用户 ID
     * @param biz             业务 ID
     * @param derivationIndex 派生索引
     * @return 派生密钥
     */
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.APTOS, biz, userId, derivationIndex);
    }

    /**
     * 派生密钥并计算 Aptos 地址（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @return Aptos 地址（0x 前缀的十六进制字符串）
     */
    public String address(long derivationIndex) {
        return address(derive(derivationIndex).publicKey());
    }

    /**
     * 对消息进行 Ed25519 签名（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @param message         待签名消息
     * @return 签名（64 字节）
     */
    public byte[] sign(long derivationIndex, byte[] message) {
        return provider().sign(Ed25519Chain.APTOS, derivationIndex, message);
    }

    /**
     * 对消息进行 Ed25519 签名（完整模式）。
     *
     * @param userId          用户 ID
     * @param biz             业务 ID
     * @param derivationIndex 派生索引
     * @param message         待签名消息
     * @return 签名（64 字节）
     */
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return sign(derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.APTOS, biz, userId, derivationIndex, message);
    }

    /**
     * 从 Ed25519 公钥计算 Aptos 地址。
     *
     * <p>计算公式：SHA3-256(publicKey || 0x00)，输出为 0x 前缀的十六进制字符串。</p>
     *
     * @param publicKey Ed25519 公钥（32 字节）
     * @return Aptos 地址
     * @throws IllegalStateException 如果 SHA3-256 算法不可用
     */
    public static String address(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            digest.update(publicKey);
            digest.update(APTOS_ED25519_SCHEME);
            return AptosHex.withPrefix(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required for Aptos address derivation", e);
        }
    }

    /**
     * 获取当前可用的密钥提供者（测试环境优先）。
     *
     * @return {@link Ed25519KeyProvider}
     */
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

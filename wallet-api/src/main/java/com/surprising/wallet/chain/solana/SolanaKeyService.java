package com.surprising.wallet.chain.solana;

import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.utils.TweetNaclFast;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Solana 链密钥服务，负责 Ed25519 密钥派生和 solanaj Account 创建。
 *
 * <p>使用 {@link Ed25519KeyProvider} 从主种子派生 Ed25519 密钥对（链 {@code SOLANA}），
 * 通过 TweetNaCl 生成签名密钥对，封装为 solanaj 的 {@link org.p2p.solanaj.core.Account}。</p>
 *
 * <p>地址为 Ed25519 公钥的 Base58 编码，与 Solana CLI（solana-keygen）兼容。
 * 密钥材料统一由 {@link WalletKeyMaterialProvider} 提供。</p>
 */
@Component
public class SolanaKeyService {

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /**
     * 生产环境构造函数。
     *
     * @param keyMaterial 主密钥材料提供者
     */
    public SolanaKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = java.util.Objects.requireNonNull(keyMaterial, "keyMaterial");
    }
    public boolean isConfigured() {
        return keyMaterial.isConfigured();
    }
    /**
     * 派生单个索引的 Ed25519 密钥（用于热钱包等单索引场景）。
     *
     * @param derivationIndex 派生索引
     * @return 派生的密钥
     */
    public Ed25519DerivedKey derive(long derivationIndex) {
        return keyMaterial.ed25519().derive(Ed25519Chain.SOLANA, derivationIndex);
    }
    /**
     * 派生多级索引的 Ed25519 密钥（userId=0, biz=0 回退到单索引派生）。
     *
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @return 派生的密钥
     */
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return keyMaterial.ed25519().derive(Ed25519Chain.SOLANA, biz, userId, derivationIndex);
    }
    /**
     * 创建 solanaj Account（单索引）。
     *
     * @param derivationIndex 派生索引
     * @return 可用于交易签名的 Account 对象
     */
    public Account account(long derivationIndex) {
        byte[] seed = derive(derivationIndex).privateSeed();
        TweetNaclFast.Signature.KeyPair keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        Arrays.fill(seed, (byte) 0);
        return new Account(keyPair.getSecretKey());
    }
    /**
     * 创建 solanaj Account（多级索引）。
     *
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @return 可用于交易签名的 Account 对象
     */
    public Account account(long userId, int biz, long derivationIndex) {
        byte[] seed = derive(userId, biz, derivationIndex).privateSeed();
        TweetNaclFast.Signature.KeyPair keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        Arrays.fill(seed, (byte) 0);
        return new Account(keyPair.getSecretKey());
    }
}

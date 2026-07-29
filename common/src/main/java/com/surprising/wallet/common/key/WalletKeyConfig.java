package com.surprising.wallet.common.key;

/**
 * 钱包密钥配置记录，存储各方签名种子及 Ed25519 密钥种子等敏感数据。
 *
 * <p>包含以下字段：</p>
 * <ul>
 *   <li>{@code sig1Seed} - 签名方 1 的 BIP32 种子</li>
 *   <li>{@code sig2Seed} - 签名方 2 的 BIP32 种子</li>
 *   <li>{@code recoverySeed} - 恢复密钥的 BIP32 种子</li>
 *   <li>{@code ed25519Seed} - Ed25519 密钥种子</li>
 * </ul>
 *
 * @see WalletKeyMaterialProvider
 */
public record WalletKeyConfig(
        String sig1Seed,
        String sig2Seed,
        String recoverySeed,
        String ed25519Seed) {
}

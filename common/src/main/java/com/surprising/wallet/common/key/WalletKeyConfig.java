package com.surprising.wallet.common.key;

import java.time.OffsetDateTime;

/**
 * 钱包密钥配置记录，存储各方签名种子及 Ed25519 密钥种子等敏感数据。
 *
 * <p>包含以下字段：</p>
 * <ul>
 *   <li>{@code sig1Seed} - 签名方 1 的 BIP32 种子</li>
 *   <li>{@code sig2Seed} - 签名方 2 的 BIP32 种子</li>
 *   <li>{@code recoverySeed} - 恢复密钥的 BIP32 种子</li>
 *   <li>{@code ed25519Seed} - Ed25519 密钥种子</li>
 *   <li>{@code createdAt} / {@code updatedAt} / {@code updatedBy} - 审计字段</li>
 * </ul>
 *
 * @see WalletKeyMaterialProvider
 * @see WalletKeyConfigStore
 */
public record WalletKeyConfig(
        String sig1Seed,
        String sig2Seed,
        String recoverySeed,
        String ed25519Seed,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String updatedBy) {
}

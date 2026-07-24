package com.surprising.wallet.chain.ton;

import com.iwebpp.crypto.TweetNaclFast;
import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;
import org.ton.ton4j.utils.Utils;

/**
 * TON 密钥服务，基于 Ed25519 算法生成 Wallet V4R2 密钥对。
 *
 * <p>密钥派生使用 {@link Ed25519KeyProvider}，从主种子按 TON 链的派生路径生成。
 * 支持 BIP44 多级派生（biz、userId、index）。
 *
 * <p>生成的 {@link WalletV4R2} 实例可直接用于 ton4j 的消息签名和 BOC 构造。
 * 子钱包 ID（subwallet_id）使用标准常量 {@link #WALLET_V4R2_SUBWALLET_ID}。
 *
 * @see com.surprising.wallet.common.key.Ed25519KeyProvider
 * @see com.surprising.wallet.common.key.WalletKeyMaterialProvider
 * @see WalletV4R2
 */
@Component
public
class TonKeyService {

    /**
     * Wallet V4R2 标准子钱包 ID。
     *
     * <p>该值来自 TON 参考钱包实现。ton4j 的 getWalletId() 会查询链上 provider，
     * 因此离线 BOC 构造必须使用此本地常量。
     */
    public static final long WALLET_V4R2_SUBWALLET_ID = 698_983_191L;

    /** 主密钥材料提供者（生产环境） */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者（非 null 时优先使用） */
    private final Ed25519KeyProvider testProvider;

    /**
     * 生产环境构造函数。
     *
     * @param keyMaterial 主密钥材料提供者
     */
    @Autowired
    public TonKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }

    /**
     * 测试环境构造函数（使用编码的 master seed）。
     *
     * @param encodedMasterSeed 编码后的主种子
     */
    public TonKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }

    /**
     * 派生单个索引的 Ed25519 密钥（热钱包等单索引场景）。
     *
     * @param derivationIndex 派生索引
     * @return 派生密钥
     */
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.TON, derivationIndex);
    }
    /**
     * 派生多级索引的 Ed25519 密钥（userId=0, biz=0 回退到单索引）。
     *
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @return 派生密钥
     */
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.TON, biz, userId, derivationIndex);
    }
    public TweetNaclFast.Signature.KeyPair keyPair(long derivationIndex) {
        return Utils.generateSignatureKeyPairFromSeed(derive(derivationIndex).privateSeed());
    }
    public TweetNaclFast.Signature.KeyPair keyPair(long userId, int biz, long derivationIndex) {
        return Utils.generateSignatureKeyPairFromSeed(derive(userId, biz, derivationIndex).privateSeed());
    }
    public WalletV4R2 wallet(long derivationIndex) {
        return WalletV4R2.builder()
                .keyPair(keyPair(derivationIndex))
                .walletId(WALLET_V4R2_SUBWALLET_ID)
                .wc(0)
                .build();
    }
    public WalletV4R2 wallet(long userId, int biz, long derivationIndex) {
        return WalletV4R2.builder()
                .keyPair(keyPair(userId, biz, derivationIndex))
                .walletId(WALLET_V4R2_SUBWALLET_ID)
                .wc(0)
                .build();
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

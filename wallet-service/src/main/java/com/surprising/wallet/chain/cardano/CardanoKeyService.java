package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.base.Bech32;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Cardano 链密钥服务，负责 Ed25519 密钥派生和 Enterprise 地址生成。
 *
 * <p>Cardano 地址使用 Blake2b224 对公钥做哈希生成 Payment Key Hash（28 字节），
 * 然后按 Enterprise 地址格式（header 0x60 | networkId）构造 payload，
 * 最后用 Bech32 编码为 addr1...（主网）或 addr_test1...（测试网）地址。</p>
 */
@Component
public
class CardanoKeyService {

    /** Payment Key Hash 长度：28 字节 */
    private static final int PAYMENT_KEY_HASH_LENGTH = 28;

    /** Enterprise 地址类型标识：0x60 */
    private static final int ENTERPRISE_KEY_ADDRESS_TYPE = 0x60;

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者 */
    private final Ed25519KeyProvider testProvider;

    /**
     * 构造 {@code CardanoKeyService}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public CardanoKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    /**
     * 构造 {@code CardanoKeyService}，初始化该组件运行所需的状态和依赖。
     */
    public CardanoKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    /**
     * 判断 {@code isConfigured} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.CARDANO, derivationIndex);
    }
    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.CARDANO, biz, userId, derivationIndex);
    }
    /**
     * 添加 {@code address} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public String address(long userId, int biz, long derivationIndex, boolean mainnet) {
        return enterpriseAddress(derive(userId, biz, derivationIndex).publicKey(), mainnet);
    }
    /**
     * 执行 {@code enterpriseAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static String enterpriseAddress(byte[] publicKey, boolean mainnet) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("Cardano Ed25519 public key must be 32 bytes");
        }
        int networkId = mainnet ? 1 : 0;
        byte[] payload = new byte[1 + PAYMENT_KEY_HASH_LENGTH];
        payload[0] = (byte) (ENTERPRISE_KEY_ADDRESS_TYPE | networkId);
        System.arraycopy(paymentKeyHash(publicKey), 0, payload, 1, PAYMENT_KEY_HASH_LENGTH);
        return Bech32.encodeBytes(Bech32.Encoding.BECH32, mainnet ? "addr" : "addr_test", payload);
    }
    /**
     * 判断 {@code isValidAddress} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public static boolean isValidAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (!value.startsWith("addr1") && !value.startsWith("addr_test1")) {
            return false;
        }
        boolean mainnet = value.startsWith("addr1");
        String hrp = mainnet ? "addr" : "addr_test";
        byte[] payload;
        try {
            payload = Bech32.decodeBytes(value, hrp, Bech32.Encoding.BECH32);
        } catch (RuntimeException e) {
            return false;
        }
        if (payload.length != 1 + PAYMENT_KEY_HASH_LENGTH) {
            return false;
        }
        int header = payload[0] & 0xff;
        return (header & 0xf0) == ENTERPRISE_KEY_ADDRESS_TYPE
                && (header & 0x0f) == (mainnet ? 1 : 0);
    }
    /**
     * 执行 {@code paymentKeyHash} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static byte[] paymentKeyHash(byte[] publicKey) {
        Blake2bDigest digest = new Blake2bDigest(PAYMENT_KEY_HASH_LENGTH * Byte.SIZE);
        digest.update(publicKey, 0, publicKey.length);
        byte[] hash = new byte[PAYMENT_KEY_HASH_LENGTH];
        digest.doFinal(hash, 0);
        return hash;
    }
    /**
     * 获取或查询 {@code provider} 对应的数据，并向调用方返回当前业务状态。
     */
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

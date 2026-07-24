package com.surprising.wallet.chain.near;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HexFormat;

/**
 * NEAR 链密钥服务，负责 Ed25519 密钥派生、签名和地址（账户 ID）管理。
 *
 * <p>NEAR 的地址就是 Ed25519 公钥的十六进制编码（64 位 hex）。同时支持隐式账户
 * （64 位 hex）和命名账户（如 alice.near）两种地址格式。</p>
 *
 * <p>提供 {@link #isValidAccountId} 方法用于验证命名账户 ID 格式的合法性。</p>
 */
@Component
public
class NearKeyService {

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者 */
    private final Ed25519KeyProvider testProvider;
    @Autowired
    public NearKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public NearKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.NEAR, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.NEAR, biz, userId, derivationIndex);
    }
    public String address(long userId, int biz, long derivationIndex) {
        return address(derive(userId, biz, derivationIndex).publicKey());
    }
    public String publicKeyBase58(long userId, int biz, long derivationIndex) {
        return Base58.encode(derive(userId, biz, derivationIndex).publicKey());
    }
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return provider().sign(Ed25519Chain.NEAR, derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.NEAR, biz, userId, derivationIndex, message);
    }
    public static String address(byte[] publicKey) {
        return HexFormat.of().formatHex(publicKey);
    }
    public static boolean isValidAccountId(String accountId) {
        String value = accountId == null ? "" : accountId.trim();
        if (value.matches("^[0-9a-f]{64}$")) {
            return true;
        }
        if (value.length() < 2 || value.length() > 64 || !value.equals(value.toLowerCase())) {
            return false;
        }
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            return false;
        }
        String[] parts = value.split("\\.");
        for (String part : parts) {
            if (part.isBlank()
                    || part.startsWith("-")
                    || part.startsWith("_")
                    || part.endsWith("-")
                    || part.endsWith("_")
                    || !part.matches("^[a-z0-9]([a-z0-9_-]*[a-z0-9])?$")) {
                return false;
            }
        }
        return true;
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

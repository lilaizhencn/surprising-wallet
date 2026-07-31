package com.surprising.wallet.chain.near;

import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;
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
    /**
     * 构造 {@code NearKeyService}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public NearKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    /**
     * 构造 {@code NearKeyService}，初始化该组件运行所需的状态和依赖。
     */
    public NearKeyService(String encodedMasterSeed) {
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
        return provider().derive(Ed25519Chain.NEAR, derivationIndex);
    }
    /**
     * 构建或生成 {@code derive} 对应的结果，并执行输入和状态校验。
     */
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.NEAR, biz, userId, derivationIndex);
    }
    /**
     * 添加 {@code address} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public String address(long userId, int biz, long derivationIndex) {
        return address(derive(userId, biz, derivationIndex).publicKey());
    }
    /**
     * 执行 {@code publicKeyBase58} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String publicKeyBase58(long userId, int biz, long derivationIndex) {
        return Base58.encode(derive(userId, biz, derivationIndex).publicKey());
    }
    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return provider().sign(Ed25519Chain.NEAR, derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.NEAR, biz, userId, derivationIndex, message);
    }
    /**
     * 添加 {@code address} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public static String address(byte[] publicKey) {
        return HexFormat.of().formatHex(publicKey);
    }
    /**
     * 判断 {@code isValidAccountId} 对应的条件是否成立，并返回明确的布尔结果。
     */
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
    /**
     * 获取或查询 {@code provider} 对应的数据，并向调用方返回当前业务状态。
     */
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;

/**
 * BIP32 密钥派生工具类，持有 sig2 的根密钥并提供 BIP44 路径派生。
 *
 * <p>在应用启动时由 {@link WalletSig2Application#initializeSig2Key} 初始化根节点。
 * 后续所有地址的签名私钥均通过路径 m/44'/{coinType}'/{biz}'/{userId}'/{index} 派生，
 * 确保与 wallet-service 的地址生成使用相同的种子和派生逻辑。
 *
 * @author atomex
 */
public class BipNodeUtil {

    /** sig2 的 BIP32 根节点，应用启动时初始化 */
    private static volatile Bip32Node NODE;

    /**
     * 初始化 sig2 BIP32 根密钥。
     *
     * @param node BIP32 根节点（必须包含私钥）
     * @throws IllegalArgumentException 如果 node 为 null 或不含私钥
     */
    public static void initialize(Bip32Node node) {
        if (node == null || !node.getEcKey().hasPrivKey()) {
            throw new IllegalArgumentException("sig2 private BIP32 root is required");
        }
        NODE = node;
    }

    /**
     * 按 BIP44 路径派生地址对应的 BIP32 节点及私钥。
     *
     * <p>派生路径：m/44'/{coinType}'/{biz}'/{userId}'/{index}
     *
     * @param address  地址信息（提供 biz、userId、index）
     * @param currency 资产元数据（提供 derivationCoinType）
     * @return 派生后的 BIP32 节点（包含私钥）
     */
    public static Bip32Node getBipNODE(Address address, AssetRuntimeMetadata currency) {
        Bip32Node node = requireRoot().getChild(44)
                .getChild(currency.getDerivationCoinType())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
        return node;
    }

    /**
     * 获取 sig2 BIP32 根节点。
     *
     * @return BIP32 根节点
     */
    public static Bip32Node getMainBipNODE() {
        return requireRoot();
    }

    /**
     * 校验并返回根节点，若未初始化则抛出异常。
     *
     * @return 已初始化的 BIP32 根节点
     * @throws IllegalStateException 如果根节点尚未初始化
     */
    private static Bip32Node requireRoot() {
        Bip32Node node = NODE;
        if (node == null) {
            throw new IllegalStateException("sig2 key material is not initialized");
        }
        return node;
    }
}

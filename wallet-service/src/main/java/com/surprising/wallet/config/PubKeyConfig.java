package com.surprising.wallet.config;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 公钥配置组件，负责生成 3-of-2 多签地址（Segwit 与 Legacy）。
 *
 * <p>该类基于三把 BIP32 根公钥（签名方1、签名方2、恢复方）派生子密钥，
 * 组合生成多签地址及其元数据。支持生产环境（通过 {@link WalletKeyMaterialProvider} 注入）
 * 和测试环境（直接传入 {@link Bip32Node}）两种模式。</p>
 *
 * <p>地址派生路径为 {@code m/44'/currency'/biz'/userId'/index}。</p>
 *
 * @see WalletKeyMaterialProvider
 * @see SegwitMultiSignAddressGenerator
 * @see LegacyMultiSignAddressGenerator
 */
@Component
public
class PubKeyConfig {
    private static final HexFormat HEX = HexFormat.of();

    /** 生产环境密钥材料提供者，注入时为非空；测试模式为 null。 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试模式下的三把根公钥，生产模式下为 null。 */
    private final Bip32Node[] testNodes;

    /**
     * 生产环境构造器，从 {@link WalletKeyMaterialProvider} 获取签名密钥。
     *
     * @param keyMaterial 密钥材料提供者
     */
    @Autowired
    public PubKeyConfig(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testNodes = null;
    }

    /**
     * 测试环境构造器，直接指定三把 BIP32 根公钥。
     *
     * @param node1 签名方1根公钥
     * @param node2 签名方2根公钥
     * @param node3 恢复方根公钥
     */
    public PubKeyConfig(Bip32Node node1, Bip32Node node2, Bip32Node node3) {
        this.keyMaterial = null;
        this.testNodes = new Bip32Node[]{node1, node2, node3};
    }

    /** @return 签名方1的 BIP32 根公钥 */
    public Bip32Node node1() {
        return testNodes == null ? keyMaterial.sig1PublicRoot() : testNodes[0];
    }

    /** @return 签名方2的 BIP32 根公钥 */
    public Bip32Node node2() {
        return testNodes == null ? keyMaterial.sig2PublicRoot() : testNodes[1];
    }

    /** @return 恢复方的 BIP32 根公钥 */
    public Bip32Node node3() {
        return testNodes == null ? keyMaterial.recoveryPublicRoot() : testNodes[2];
    }

    /**
     * 生成 3-of-2 多签地址（仅返回地址字符串）。
     *
     * @param currency 币种类型（BIP44 coin type）
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @return 多签地址字符串
     */
    public String genThree_TwoAddress(int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(currency, userId, biz, index).address;
    }

    /**
     * 生成 3-of-2 多签地址元数据（使用默认网络参数）。
     *
     * @param currency 币种类型
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @return 地址元数据，包含地址、路径、见证脚本等信息
     */
    public AddressMetadata genThreeTwoAddressMetadata(int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(Constants.NET_PARAMS, currency, userId, biz, index);
    }

    /**
     * 生成 3-of-2 多签地址元数据（指定网络参数）。
     *
     * @param params   比特币网络参数
     * @param currency 币种类型
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @return 地址元数据
     */
    public AddressMetadata genThreeTwoAddressMetadata(NetworkParameters params, int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(params, currency, userId, biz, index, node1(), node2(), node3());
    }

    /**
     * 静态方法：基于三把给定的根公钥生成 Segwit 3-of-2 多签地址元数据。
     *
     * @param params   比特币网络参数
     * @param currency 币种类型
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @param node1    签名方1根公钥
     * @param node2    签名方2根公钥
     * @param node3    恢复方根公钥
     * @return 地址元数据
     */
    public static AddressMetadata genThreeTwoAddressMetadata(NetworkParameters params, int currency, int userId,
                                                             int biz, int index, Bip32Node node1,
                                                             Bip32Node node2, Bip32Node node3) {
        SegwitMultiSignAddressGenerator g = new SegwitMultiSignAddressGenerator();
        ECKey key1 = childKey(node1, currency, biz, userId, index);
        ECKey key2 = childKey(node2, currency, biz, userId, index);
        ECKey key3 = childKey(node3, currency, biz, userId, index);
        g.addECKey(key1);
        g.addECKey(key2);
        g.addECKey(key3);
        String address = g.generateAddress(params, 2);
        String pubKeys = Stream.of(key1, key2, key3)
                .map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
        String path = String.format("m/44/%d/%d/%d/%d", currency, biz, userId, index);
        return new AddressMetadata(address, path, "", g.getWitnessScriptStr(), pubKeys);
    }

    /**
     * 生成 Legacy（P2SH）3-of-2 多签地址元数据。
     *
     * @param params   比特币网络参数
     * @param coinType 币种类型
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @return 地址元数据
     */
    public AddressMetadata genLegacyThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index) {
        return genLegacyThreeTwoAddressMetadata(params, coinType, userId, biz, index, node1(), node2(), node3());
    }

    /**
     * 静态方法：基于三把给定的根公钥生成 Legacy（P2SH）3-of-2 多签地址元数据。
     *
     * @param params   比特币网络参数
     * @param coinType 币种类型
     * @param userId   用户 ID
     * @param biz      业务类型
     * @param index    地址索引
     * @param node1    签名方1根公钥
     * @param node2    签名方2根公钥
     * @param node3    恢复方根公钥
     * @return 地址元数据
     */
    public static AddressMetadata genLegacyThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index,
            Bip32Node node1, Bip32Node node2, Bip32Node node3) {
        LegacyMultiSignAddressGenerator generator = new LegacyMultiSignAddressGenerator();
        ECKey key1 = childKey(node1, coinType, biz, userId, index);
        ECKey key2 = childKey(node2, coinType, biz, userId, index);
        ECKey key3 = childKey(node3, coinType, biz, userId, index);
        generator.addECKey(key1);
        generator.addECKey(key2);
        generator.addECKey(key3);
        String address = generator.generateAddress(params, 2);
        String pubKeys = Stream.of(key1, key2, key3)
                .map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
        String path = String.format("m/44/%d/%d/%d/%d", coinType, biz, userId, index);
        return new AddressMetadata(address, path, generator.getRedeemScriptHex(), "", pubKeys);
    }
    /**
     * 按 BIP44 路径从根节点派生子密钥：m/44'/currency'/biz'/userId'/index。
     *
     * @param node     根节点
     * @param currency 币种类型
     * @param biz      业务类型
     * @param userId   用户 ID
     * @param index    地址索引
     * @return 派生出的 ECKey
     */
    private static ECKey childKey(Bip32Node node, int currency, int biz, int userId, int index) {
        return node.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
    }

    /**
     * 多签地址元数据，包含地址、派生路径、脚本及公钥信息。
     */
    public static class AddressMetadata {
        /** 链上地址 */
        private final String address;
        /** BIP44 派生路径 */
        private final String path;
        /** Legacy 赎回脚本（P2SH redeem script） */
        private final String redeemScript;
        /** Segwit 见证脚本（witness script） */
        private final String witnessScript;
        /** 逗号分隔的三把公钥（十六进制） */
        private final String publicKeys;

        private AddressMetadata(String address, String path, String redeemScript,
                                String witnessScript, String publicKeys) {
            this.address = address;
            this.path = path;
            this.redeemScript = redeemScript;
            this.witnessScript = witnessScript;
            this.publicKeys = publicKeys;
        }

        public String getAddress() {
            return address;
        }

        public String getPath() {
            return path;
        }

        public String getRedeemScript() {
            return redeemScript;
        }

        public String getWitnessScript() {
            return witnessScript;
        }

        public String getPublicKeys() {
            return publicKeys;
        }
    }
}

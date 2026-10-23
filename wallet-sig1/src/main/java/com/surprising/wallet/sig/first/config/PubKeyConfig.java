package com.surprising.wallet.sig.first.config;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 多签公钥配置，管理 sig1、sig2、recovery 三组 BIP32 公钥的派生与脚本生成。
 *
 * <p>用于 BTC-like 链的 P2WSH 见证脚本和 P2SH 赎回脚本生成。
 * 三组公钥按 BIP44 路径 m/44'/{coinType}'/{biz}'/{userId}'/{index} 派生，
 * 组成 2-of-3 多签地址。
 *
 */
@Slf4j
@Component
public class PubKeyConfig {

    /** 当前签名角色的密钥材料。 */
    private final WalletKeyMaterialProvider keyMaterial;

    /**
     * 生产模式构造：注入当前签名角色的密钥材料。
     *
     * @param keyMaterial sig1 模式下的密钥材料提供者
     */
    public PubKeyConfig(@Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    /**
     * 生成 P2WSH 见证脚本（SegWit）。
     *
     * <p>按 BIP44 路径从三组公钥派生子密钥，组成 2-of-3 多签 P2WSH 地址。
     * 日志中会输出生成的 bech32 地址用于校验。
     *
     * @param a  地址信息（包含 biz、userId、index）
     * @param ce 资产运行时元数据（包含 bip44CoinType）
     * @return 见证脚本十六进制字符串
     */
    public String genWitnessScript(Address a, AssetRuntimeMetadata ce) {
        ECKey k1 = derivePublicKey(keyMaterial.sig1PublicRoot(), a, ce);
        ECKey k2 = derivePublicKey(keyMaterial.sig2PublicRoot(), a, ce);
        ECKey k3 = derivePublicKey(keyMaterial.recoveryPublicRoot(), a, ce);
        var generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(k1);
        generator.addECKey(k2);
        generator.addECKey(k3);
        String address = generator.generateAddress(TestNet3Params.get(), 2);
        log.info("P2WSH witnessScript: bech32={}, addrRef={}", address, a.getAddress());
        return generator.getWitnessScriptStr();
    }
    /**
     * 生成 P2SH 赎回脚本（Legacy 多签），用于 DOGE、BCH 等链。
     *
     * <p>按 BIP44 路径从三组公钥派生子密钥，组成 2-of-3 多签 P2SH 赎回脚本。
     * 如果地址中已存储 redeemScript 且与派生结果不一致，将抛出异常以保证密钥一致性。
     *
     * @param a  地址信息
     * @param ce 资产运行时元数据
     * @return 赎回脚本十六进制字符串
     * @throws IllegalStateException 如果存储的 redeemScript 与派生结果不一致
     */
    public String genRedeemScript(Address a, AssetRuntimeMetadata ce) {
        ECKey k1 = derivePublicKey(keyMaterial.sig1PublicRoot(), a, ce);
        ECKey k2 = derivePublicKey(keyMaterial.sig2PublicRoot(), a, ce);
        ECKey k3 = derivePublicKey(keyMaterial.recoveryPublicRoot(), a, ce);
        var generator = new LegacyMultiSignAddressGenerator();
        generator.addECKey(k1);
        generator.addECKey(k2);
        generator.addECKey(k3);
        generator.generateAddress(DogecoinNetworkParameters.testnet(), 2);
        String redeemScript = generator.getRedeemScriptHex();
        if (a.getRedeemScript() != null && !a.getRedeemScript().isBlank()
                && !a.getRedeemScript().equalsIgnoreCase(redeemScript)) {
            throw new IllegalStateException(
                    "stored redeemScript does not match derived multisig keys");
        }
        return redeemScript;
    }

    private static ECKey derivePublicKey(Bip32Node root, Address address, AssetRuntimeMetadata asset) {
        return root.getChild(44).getChild(asset.getBip44CoinType()).getChild(address.getBiz())
                .getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
    }

}

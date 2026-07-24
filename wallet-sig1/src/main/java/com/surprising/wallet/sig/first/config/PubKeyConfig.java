package com.surprising.wallet.sig.first.config;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata; import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.utils.Constants; import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import lombok.Data; import lombok.extern.slf4j.Slf4j; import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired; import org.springframework.stereotype.Component;
/**
 * 多签公钥配置，管理 sig1、sig2、recovery 三组 BIP32 公钥的派生与脚本生成。
 *
 * <p>用于 BTC-like 链的 P2WSH 见证脚本和 P2SH 赎回脚本生成。
 * 三组公钥按 BIP44 路径 m/44'/{coinType}'/{biz}'/{userId}'/{index} 派生，
 * 组成 2-of-3 多签地址。
 *
 * <p>同时支持生产模式（从数据库加载密钥材料）和测试模式（直接注入 Bip32Node）。
 */
@Slf4j
@Component
@Data
public class PubKeyConfig {

    /** 密钥材料提供者（生产模式） */
    private final WalletKeyMaterialProvider keyMaterial;
    /** 测试用 BIP32 节点（测试模式），依次为 sig1、sig2、recovery */
    private final Bip32Node[] testNodes;

    /**
     * 生产模式构造：从数据库加载密钥材料。
     *
     * @param keyMaterial sig1 模式下的密钥材料提供者
     */
    @Autowired
    public PubKeyConfig(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testNodes = null;
    }

    /**
     * 测试模式构造：直接注入三组 BIP32 节点。
     *
     * @param node1 sig1 BIP32 节点
     * @param node2 sig2 BIP32 节点
     * @param node3 recovery BIP32 节点
     */
    public PubKeyConfig(Bip32Node node1, Bip32Node node2, Bip32Node node3) {
        this.keyMaterial = null;
        this.testNodes = new Bip32Node[]{node1, node2, node3};
    }

    /** @return sig1 的公钥根节点 */
    private Bip32Node node1() { return testNodes == null ? keyMaterial.sig1PublicRoot() : testNodes[0]; }
    /** @return sig2 的公钥根节点 */
    private Bip32Node node2() { return testNodes == null ? keyMaterial.sig2PublicRoot() : testNodes[1]; }
    /** @return recovery 的公钥根节点 */
    private Bip32Node node3() { return testNodes == null ? keyMaterial.recoveryPublicRoot() : testNodes[2]; }

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
        ECKey k1=node1().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=node2().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=node3().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        SegwitMultiSignAddressGenerator g=new SegwitMultiSignAddressGenerator(); g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        String addr=g.generateAddress(Constants.NET_PARAMS,2);
        log.info("P2WSH witnessScript: bech32={}, addrRef={}",addr,a.getAddress());
        return g.getWitnessScriptStr();
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
        ECKey k1=node1().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=node2().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=node3().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        LegacyMultiSignAddressGenerator g=new LegacyMultiSignAddressGenerator(); g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        g.generateAddress(com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters.testnet(),2);
        String redeemScript=g.getRedeemScriptHex();
        if(a.getRedeemScript()!=null&&!a.getRedeemScript().isBlank()&&!a.getRedeemScript().equalsIgnoreCase(redeemScript)){
            throw new IllegalStateException(
                    "stored redeemScript does not match derived multisig keys");
        }
        return redeemScript;
    }
}

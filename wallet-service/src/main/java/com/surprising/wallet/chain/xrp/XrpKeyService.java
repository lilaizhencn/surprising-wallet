package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.PubKeyConfig;
import org.bitcoinj.crypto.ECKey;
import org.spongycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;
import org.xrpl.xrpl4j.codec.addresses.KeyType;
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray;
import org.xrpl.xrpl4j.crypto.keys.PrivateKey;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;

import java.util.Arrays;

/**
 * XRP 钱包密钥服务。
 *
 * <p>负责 XRPL 地址的 secp256k1 密钥派生和签名密钥管理。
 * 与 XRPL 交互使用 xrpl4j 库的 {@link org.xrpl.xrpl4j.crypto.keys.PublicKey} 和
 * {@link org.xrpl.xrpl4j.crypto.keys.PrivateKey} 类型。
 *
 * <p>地址派生遵循 BIP44 路径 m/44'/coinType'/biz'/userId'/index。
 * 签名密钥通过 {@link AccountSecp256k1KeyService} 管理，
 * 经典地址通过 {@link org.xrpl.xrpl4j.codec.addresses.KeyType#SECP256K1} 派生。
 */
@Service
public
class XrpKeyService {

    /** 多签公钥配置 */
    private final PubKeyConfig pubKeyConfig;

    /** 签名者密钥服务 */
    private final AccountSecp256k1KeyService signerKeyService;

    /**
     * @param pubKeyConfig      多签公钥配置
     * @param signerKeyService  签名者密钥服务
     */
    public XrpKeyService(PubKeyConfig pubKeyConfig, AccountSecp256k1KeyService signerKeyService) {
        this.pubKeyConfig = pubKeyConfig;
        this.signerKeyService = signerKeyService;
    }
    /**
     * 派生 XRP 地址（基于 BIP44 路径）。
     *
     * @param profile         链配置
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @return XRP 经典地址（r 开头）
     */
    public String address(AccountChainProfile profile, long userId, int biz, long derivationIndex) {
        ECKey ecKey = pubKeyConfig.node2().getChild(44)
                .getChild(ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType()))
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(derivationIndex))
                .getEcKey();
        return address(ecKey);
    }
    /**
     * 获取用于签名的私钥。
     *
     * @param profile 链配置
     * @param address 地址记录
     * @return xrpl4j PrivateKey（SECP256K1）
     */
    public PrivateKey privateKey(AccountChainProfile profile, ChainAddressRecord address) {
        return privateKey(signerKeyService.key(profile, address));
    }
    public static String address(ECKey ecKey) {
        return publicKey(ecKey).deriveAddress().value();
    }
    public static PublicKey publicKey(ECKey ecKey) {
        return PublicKey.fromBase16EncodedPublicKey(Hex.toHexString(ecKey.getPubKey()).toUpperCase());
    }
    public static PrivateKey privateKey(ECKey ecKey) {
        byte[] privateBytes = ecKey.getPrivKeyBytes();
        try {
            return PrivateKey.fromNaturalBytes(UnsignedByteArray.of(privateBytes), KeyType.SECP256K1);
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
        }
    }
}

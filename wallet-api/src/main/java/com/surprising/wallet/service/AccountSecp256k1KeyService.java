package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;

/**
 * 账户链 secp256k1 密钥派生与 EVM 签名凭证构造。
 */
@Service
public class AccountSecp256k1KeyService {
    /**
     * 保存 {@code keyMaterial}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final WalletKeyMaterialProvider keyMaterial;
    /**
     * 构造 {@code AccountSecp256k1KeyService}，初始化该组件运行所需的状态和依赖。
     */
    public AccountSecp256k1KeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
    }
    /**
     * 执行 {@code key} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public ECKey key(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyMaterial.sig2Root().getChild(44)
                .getChild(ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType()))
                .getChild(from.getBiz())
                .getChild(Math.toIntExact(from.getUserId()))
                .getChild(Math.toIntExact(from.getAddressIndex()))
                .getEcKey();
        if (!ecKey.hasPrivKey()) {
            throw new IllegalStateException("account-chain signer root must be a private BIP32 key");
        }
        return ecKey;
    }

    public Credentials credentials(AccountChainProfile profile, ChainAddressRecord address) {
        ECKey key = key(profile, address);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(key.getPrivKey(), 64));
    }
}

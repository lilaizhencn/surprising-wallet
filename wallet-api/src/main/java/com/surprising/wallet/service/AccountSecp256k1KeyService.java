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
    public AccountSecp256k1KeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
    }
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

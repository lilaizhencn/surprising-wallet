package com.surprising.wallet.service.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;

/**
 * Private secp256k1 derivation for account-chain signing.
 * Uses the sig2 seed from the database-backed wallet keyset.
 */
@Service
public class AccountSecp256k1KeyService {
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

}

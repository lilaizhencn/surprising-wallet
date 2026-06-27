package com.surprising.wallet.service.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Private secp256k1 derivation for account-chain signing.
 * Address derivation still uses wallet_public_key; this service only signs live
 * account-chain withdrawals and collections when the signer root is injected.
 */
@Service
public class AccountSecp256k1KeyService {
    private final String signerMasterKey;
    private volatile Bip32Node signerRoot;

    public AccountSecp256k1KeyService(
            @Value("${sw.wallet.account-chain.signer-master-key:${SW_SIG2_MASTER_KEY:}}") String signerMasterKey) {
        this.signerMasterKey = signerMasterKey == null ? "" : signerMasterKey.trim();
    }

    public ECKey key(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = root().getChild(44)
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

    private Bip32Node root() {
        Bip32Node local = signerRoot;
        if (local != null) {
            return local;
        }
        if (!StringUtils.hasText(signerMasterKey)) {
            throw new IllegalStateException(
                    "missing account-chain signer key: set SW_SIG2_MASTER_KEY for wallet-server");
        }
        synchronized (this) {
            if (signerRoot == null) {
                signerRoot = Bip32Node.decode(signerMasterKey);
            }
            return signerRoot;
        }
    }
}

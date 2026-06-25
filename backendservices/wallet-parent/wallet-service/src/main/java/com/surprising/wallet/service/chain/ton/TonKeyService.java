package com.surprising.wallet.service.chain.ton;

import com.iwebpp.crypto.TweetNaclFast;
import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;
import org.ton.ton4j.utils.Utils;

@Component
public class TonKeyService {
    /**
     * Standard Wallet V4 subwallet id used by TON reference wallets.
     * ton4j's getWalletId() queries a provider, so signing code must use this
     * local constant for offline BOC construction.
     */
    public static final long WALLET_V4R2_SUBWALLET_ID = 698_983_191L;

    private final String encodedMasterSeed;
    private volatile Ed25519KeyProvider provider;

    public TonKeyService(@Value("${sw.wallet.ed25519.master-seed:}") String encodedMasterSeed) {
        this.encodedMasterSeed = encodedMasterSeed;
    }

    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.TON, derivationIndex);
    }

    public TweetNaclFast.Signature.KeyPair keyPair(long derivationIndex) {
        return Utils.generateSignatureKeyPairFromSeed(derive(derivationIndex).privateSeed());
    }

    public WalletV4R2 wallet(long derivationIndex) {
        return WalletV4R2.builder()
                .keyPair(keyPair(derivationIndex))
                .walletId(WALLET_V4R2_SUBWALLET_ID)
                .wc(0)
                .build();
    }

    private Ed25519KeyProvider provider() {
        Ed25519KeyProvider result = provider;
        if (result == null) {
            synchronized (this) {
                result = provider;
                if (result == null) {
                    result = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
                    provider = result;
                }
            }
        }
        return result;
    }
}

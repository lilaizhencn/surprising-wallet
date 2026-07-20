package com.surprising.wallet.service.chain.ton;

import com.iwebpp.crypto.TweetNaclFast;
import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final WalletKeyMaterialProvider keyMaterial;
    private final Ed25519KeyProvider testProvider;

    @Autowired
    public TonKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }

    public TonKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }

    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.TON, derivationIndex);
    }

    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.TON, biz, userId, derivationIndex);
    }

    public TweetNaclFast.Signature.KeyPair keyPair(long derivationIndex) {
        return Utils.generateSignatureKeyPairFromSeed(derive(derivationIndex).privateSeed());
    }

    public TweetNaclFast.Signature.KeyPair keyPair(long userId, int biz, long derivationIndex) {
        return Utils.generateSignatureKeyPairFromSeed(derive(userId, biz, derivationIndex).privateSeed());
    }

    public WalletV4R2 wallet(long derivationIndex) {
        return WalletV4R2.builder()
                .keyPair(keyPair(derivationIndex))
                .walletId(WALLET_V4R2_SUBWALLET_ID)
                .wc(0)
                .build();
    }

    public WalletV4R2 wallet(long userId, int biz, long derivationIndex) {
        return WalletV4R2.builder()
                .keyPair(keyPair(userId, biz, derivationIndex))
                .walletId(WALLET_V4R2_SUBWALLET_ID)
                .wc(0)
                .build();
    }

    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

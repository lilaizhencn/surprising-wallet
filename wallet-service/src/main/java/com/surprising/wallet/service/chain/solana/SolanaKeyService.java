package com.surprising.wallet.service.chain.solana;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.utils.TweetNaclFast;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class SolanaKeyService {
    private final WalletKeyMaterialProvider keyMaterial;
    private final Ed25519KeyProvider testProvider;

    @Autowired
    public SolanaKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }

    public SolanaKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }

    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }

    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.SOLANA, derivationIndex);
    }

    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.SOLANA, biz, userId, derivationIndex);
    }

    public Account account(long derivationIndex) {
        byte[] seed = derive(derivationIndex).privateSeed();
        TweetNaclFast.Signature.KeyPair keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        Arrays.fill(seed, (byte) 0);
        return new Account(keyPair.getSecretKey());
    }

    public Account account(long userId, int biz, long derivationIndex) {
        byte[] seed = derive(userId, biz, derivationIndex).privateSeed();
        TweetNaclFast.Signature.KeyPair keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        Arrays.fill(seed, (byte) 0);
        return new Account(keyPair.getSecretKey());
    }

    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

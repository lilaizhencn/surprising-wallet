package com.surprising.wallet.service.chain.aptos;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class AptosKeyService {
    private static final byte APTOS_ED25519_SCHEME = 0x00;
    private final WalletKeyMaterialProvider keyMaterial;
    private final Ed25519KeyProvider testProvider;

    @Autowired
    public AptosKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public AptosKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.APTOS, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.APTOS, biz, userId, derivationIndex);
    }
    public String address(long derivationIndex) {
        return address(derive(derivationIndex).publicKey());
    }
    public byte[] sign(long derivationIndex, byte[] message) {
        return provider().sign(Ed25519Chain.APTOS, derivationIndex, message);
    }
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return sign(derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.APTOS, biz, userId, derivationIndex, message);
    }
    public static String address(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            digest.update(publicKey);
            digest.update(APTOS_ED25519_SCHEME);
            return AptosHex.withPrefix(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required for Aptos address derivation", e);
        }
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

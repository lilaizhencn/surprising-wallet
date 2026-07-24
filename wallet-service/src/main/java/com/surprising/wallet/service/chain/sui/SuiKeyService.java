package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public
class SuiKeyService {
    private static final byte ED25519_SCHEME = 0x00;
    private final WalletKeyMaterialProvider keyMaterial;
    private final Ed25519KeyProvider testProvider;

    @Autowired
    public SuiKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public SuiKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.SUI, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.SUI, biz, userId, derivationIndex);
    }
    public String address(long derivationIndex) {
        return address(derive(derivationIndex).publicKey());
    }
    public byte[] sign(long derivationIndex, byte[] message) {
        return provider().sign(Ed25519Chain.SUI, derivationIndex, message);
    }
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return sign(derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.SUI, biz, userId, derivationIndex, message);
    }
    public static String address(byte[] publicKey) {
        byte[] data = new byte[1 + publicKey.length];
        data[0] = ED25519_SCHEME;
        System.arraycopy(publicKey, 0, data, 1, publicKey.length);
        return SuiHex.withPrefix(new Blake2b.Blake2b256().digest(data));
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

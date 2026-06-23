package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SuiKeyService {
    private static final byte ED25519_SCHEME = 0x00;

    private final String encodedMasterSeed;
    private volatile Ed25519KeyProvider provider;

    public SuiKeyService(@Value("${atomex.wallet.ed25519.master-seed:}") String encodedMasterSeed) {
        this.encodedMasterSeed = encodedMasterSeed;
    }

    public boolean isConfigured() {
        return encodedMasterSeed != null && !encodedMasterSeed.isBlank();
    }

    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.SUI, derivationIndex);
    }

    public String address(long derivationIndex) {
        return address(derive(derivationIndex).publicKey());
    }

    public byte[] sign(long derivationIndex, byte[] message) {
        return provider().sign(Ed25519Chain.SUI, derivationIndex, message);
    }

    public static String address(byte[] publicKey) {
        byte[] data = new byte[1 + publicKey.length];
        data[0] = ED25519_SCHEME;
        System.arraycopy(publicKey, 0, data, 1, publicKey.length);
        return SuiHex.withPrefix(new Blake2b.Blake2b256().digest(data));
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

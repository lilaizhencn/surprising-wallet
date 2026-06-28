package com.surprising.wallet.service.chain.near;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HexFormat;

@Component
public class NearKeyService {
    private final String encodedMasterSeed;
    private volatile Ed25519KeyProvider provider;

    public NearKeyService(@Value("${sw.wallet.ed25519.master-seed:}") String encodedMasterSeed) {
        this.encodedMasterSeed = encodedMasterSeed;
    }

    public boolean isConfigured() {
        return encodedMasterSeed != null && !encodedMasterSeed.isBlank();
    }

    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.NEAR, derivationIndex);
    }

    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.NEAR, biz, userId, derivationIndex);
    }

    public String address(long userId, int biz, long derivationIndex) {
        return address(derive(userId, biz, derivationIndex).publicKey());
    }

    public String publicKeyBase58(long userId, int biz, long derivationIndex) {
        return Base58.encode(derive(userId, biz, derivationIndex).publicKey());
    }

    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return provider().sign(Ed25519Chain.NEAR, derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.NEAR, biz, userId, derivationIndex, message);
    }

    public static String address(byte[] publicKey) {
        return HexFormat.of().formatHex(publicKey);
    }

    public static boolean isValidAccountId(String accountId) {
        String value = accountId == null ? "" : accountId.trim();
        if (value.matches("^[0-9a-f]{64}$")) {
            return true;
        }
        if (value.length() < 2 || value.length() > 64 || !value.equals(value.toLowerCase())) {
            return false;
        }
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            return false;
        }
        String[] parts = value.split("\\.");
        for (String part : parts) {
            if (part.isBlank()
                    || part.startsWith("-")
                    || part.startsWith("_")
                    || part.endsWith("-")
                    || part.endsWith("_")
                    || !part.matches("^[a-z0-9]([a-z0-9_-]*[a-z0-9])?$")) {
                return false;
            }
        }
        return true;
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

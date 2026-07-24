package com.surprising.wallet.service.chain.cardano;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.base.Bech32;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public
class CardanoKeyService {
    private static final int PAYMENT_KEY_HASH_LENGTH = 28;    private static final int ENTERPRISE_KEY_ADDRESS_TYPE = 0x60;    private final WalletKeyMaterialProvider keyMaterial;    private final Ed25519KeyProvider testProvider;

    @Autowired
    public CardanoKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public CardanoKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.CARDANO, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.CARDANO, biz, userId, derivationIndex);
    }
    public String address(long userId, int biz, long derivationIndex, boolean mainnet) {
        return enterpriseAddress(derive(userId, biz, derivationIndex).publicKey(), mainnet);
    }
    public static String enterpriseAddress(byte[] publicKey, boolean mainnet) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("Cardano Ed25519 public key must be 32 bytes");
        }
        int networkId = mainnet ? 1 : 0;
        byte[] payload = new byte[1 + PAYMENT_KEY_HASH_LENGTH];
        payload[0] = (byte) (ENTERPRISE_KEY_ADDRESS_TYPE | networkId);
        System.arraycopy(paymentKeyHash(publicKey), 0, payload, 1, PAYMENT_KEY_HASH_LENGTH);
        return Bech32.encodeBytes(Bech32.Encoding.BECH32, mainnet ? "addr" : "addr_test", payload);
    }
    public static boolean isValidAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (!value.startsWith("addr1") && !value.startsWith("addr_test1")) {
            return false;
        }
        boolean mainnet = value.startsWith("addr1");
        String hrp = mainnet ? "addr" : "addr_test";
        byte[] payload;
        try {
            payload = Bech32.decodeBytes(value, hrp, Bech32.Encoding.BECH32);
        } catch (RuntimeException e) {
            return false;
        }
        if (payload.length != 1 + PAYMENT_KEY_HASH_LENGTH) {
            return false;
        }
        int header = payload[0] & 0xff;
        return (header & 0xf0) == ENTERPRISE_KEY_ADDRESS_TYPE
                && (header & 0x0f) == (mainnet ? 1 : 0);
    }
    private static byte[] paymentKeyHash(byte[] publicKey) {
        Blake2bDigest digest = new Blake2bDigest(PAYMENT_KEY_HASH_LENGTH * Byte.SIZE);
        digest.update(publicKey, 0, publicKey.length);
        byte[] hash = new byte[PAYMENT_KEY_HASH_LENGTH];
        digest.doFinal(hash, 0);
        return hash;
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

package com.surprising.wallet.service.chain.cardano;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.bitcoinj.base.Bech32;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class CardanoKeyService {
    private static final int PAYMENT_KEY_HASH_LENGTH = 28;
    private static final int ENTERPRISE_KEY_ADDRESS_TYPE = 0x60;

    private final String encodedMasterSeed;
    private volatile Ed25519KeyProvider provider;

    public CardanoKeyService(@Value("${sw.wallet.ed25519.master-seed:}") String encodedMasterSeed) {
        this.encodedMasterSeed = encodedMasterSeed;
    }

    public boolean isConfigured() {
        return encodedMasterSeed != null && !encodedMasterSeed.isBlank();
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
        byte[] digest = new Blake2b.Blake2b256().digest(publicKey);
        return Arrays.copyOf(digest, PAYMENT_KEY_HASH_LENGTH);
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

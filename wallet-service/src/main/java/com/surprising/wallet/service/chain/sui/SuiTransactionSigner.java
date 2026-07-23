package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.key.Ed25519DerivedKey;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class SuiTransactionSigner {
    private static final byte ED25519_SCHEME = 0x00;
    private static final byte[] TRANSACTION_DATA_INTENT = new byte[]{0, 0, 0};

    private final SuiKeyService keyService;

    public String signTransactionBytes(long derivationIndex, String txBytesBase64) {
        return signTransactionBytes(0L, 0, derivationIndex, txBytesBase64);
    }

    public String signTransactionBytes(long userId, int biz, long derivationIndex, String txBytesBase64) {
        byte[] txBytes = Base64.getDecoder().decode(txBytesBase64);
        byte[] intentMessage = new byte[TRANSACTION_DATA_INTENT.length + txBytes.length];
        System.arraycopy(TRANSACTION_DATA_INTENT, 0, intentMessage, 0, TRANSACTION_DATA_INTENT.length);
        System.arraycopy(txBytes, 0, intentMessage, TRANSACTION_DATA_INTENT.length, txBytes.length);
        byte[] digest = new Blake2b.Blake2b256().digest(intentMessage);
        byte[] signature = keyService.sign(userId, biz, derivationIndex, digest);
        Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + signature.length + key.publicKey().length);
        out.write(ED25519_SCHEME);
        out.write(signature, 0, signature.length);
        out.write(key.publicKey(), 0, key.publicKey().length);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}

package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.key.Ed25519DerivedKey;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Sui 交易签名器。
 *
 * <p>对 BCS 序列化的交易字节添加 Intent 前缀（[0,0,0]）后计算 Blake2b256 哈希，
 * 再用 Ed25519 签名。签名结果格式为：1 字节 scheme + 64 字节签名 + 32 字节公钥。</p>
 */
@Component
@RequiredArgsConstructor
public
class SuiTransactionSigner {

    /** Ed25519 单签方案标识字节 */
    private static final byte ED25519_SCHEME = 0x00;

    /** Sui TransactionData 的 Intent 前缀 */
    private static final byte[] TRANSACTION_DATA_INTENT = new byte[]{0, 0, 0};

    /** Sui 密钥服务 */
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

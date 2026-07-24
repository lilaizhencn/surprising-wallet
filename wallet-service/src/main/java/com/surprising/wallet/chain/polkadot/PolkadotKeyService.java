package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.bitcoinj.base.Base58;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Polkadot 链密钥服务，负责 Ed25519 密钥派生和 SS58 地址编码。
 *
 * <p>SS58 地址格式：SS58 前缀字节 + 公钥（32 字节） + Blake2b512 校验和（前 2 字节），
 * 最终使用 Base58 编码。校验和计算时需在 payload 前拼接 "SS58PRE" 前缀。</p>
 *
 * <p>支持 0-16383 范围内的 SS58 前缀值（如 Polkadot 主网为 0，测试网通用为 42）。</p>
 */
@Component
public
class PolkadotKeyService {

    /** SS58 校验和计算前缀 */
    private static final byte[] SS58_PREFIX = "SS58PRE".getBytes(StandardCharsets.US_ASCII);

    /** 生产环境密钥材料 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 测试环境密钥提供者 */
    private final Ed25519KeyProvider testProvider;

    @Autowired
    public PolkadotKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testProvider = null;
    }
    public PolkadotKeyService(String encodedMasterSeed) {
        this.keyMaterial = null;
        this.testProvider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(encodedMasterSeed));
    }
    public boolean isConfigured() {
        return testProvider != null || keyMaterial.isConfigured();
    }
    public Ed25519DerivedKey derive(long derivationIndex) {
        return provider().derive(Ed25519Chain.POLKADOT, derivationIndex);
    }
    public Ed25519DerivedKey derive(long userId, int biz, long derivationIndex) {
        if (userId == 0 && biz == 0) {
            return derive(derivationIndex);
        }
        return provider().derive(Ed25519Chain.POLKADOT, biz, userId, derivationIndex);
    }
    public String address(long userId, int biz, long derivationIndex, int ss58Prefix) {
        return ss58Address(derive(userId, biz, derivationIndex).publicKey(), ss58Prefix);
    }
    public byte[] sign(long userId, int biz, long derivationIndex, byte[] message) {
        if (userId == 0 && biz == 0) {
            return provider().sign(Ed25519Chain.POLKADOT, derivationIndex, message);
        }
        return provider().sign(Ed25519Chain.POLKADOT, biz, userId, derivationIndex, message);
    }
    public static String ss58Address(byte[] publicKey, int ss58Prefix) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("Polkadot account id must be 32 bytes");
        }
        byte[] prefix = ss58PrefixBytes(ss58Prefix);
        byte[] payload = new byte[prefix.length + publicKey.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(publicKey, 0, payload, prefix.length, publicKey.length);

        byte[] checksumInput = new byte[SS58_PREFIX.length + payload.length];
        System.arraycopy(SS58_PREFIX, 0, checksumInput, 0, SS58_PREFIX.length);
        System.arraycopy(payload, 0, checksumInput, SS58_PREFIX.length, payload.length);
        byte[] checksum = new Blake2b.Blake2b512().digest(checksumInput);

        byte[] address = Arrays.copyOf(payload, payload.length + 2);
        address[address.length - 2] = checksum[0];
        address[address.length - 1] = checksum[1];
        return Base58.encode(address);
    }
    public static boolean isValidSs58Address(String address) {
        String value = address == null ? "" : address.trim();
        if (value.length() < 47 || value.length() > 50) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base58.decode(value);
        } catch (RuntimeException e) {
            return false;
        }
        if (decoded.length != 35 && decoded.length != 36) {
            return false;
        }
        int first = decoded[0] & 0xff;
        if (first >= 128) {
            return false;
        }
        int prefixLength = first < 64 ? 1 : 2;
        int publicKeyLength = decoded.length - prefixLength - 2;
        if (publicKeyLength != 32) {
            return false;
        }
        byte[] payload = Arrays.copyOf(decoded, prefixLength + publicKeyLength);
        byte[] checksumInput = new byte[SS58_PREFIX.length + payload.length];
        System.arraycopy(SS58_PREFIX, 0, checksumInput, 0, SS58_PREFIX.length);
        System.arraycopy(payload, 0, checksumInput, SS58_PREFIX.length, payload.length);
        byte[] checksum = new Blake2b.Blake2b512().digest(checksumInput);
        return decoded[decoded.length - 2] == checksum[0]
                && decoded[decoded.length - 1] == checksum[1];
    }
    private static byte[] ss58PrefixBytes(int ss58Prefix) {
        if (ss58Prefix < 0 || ss58Prefix > 16383) {
            throw new IllegalArgumentException("SS58 prefix must be between 0 and 16383");
        }
        if (ss58Prefix < 64) {
            return new byte[]{(byte) ss58Prefix};
        }
        int first = ((ss58Prefix & 0x00FC) >> 2) | 0x40;
        int second = (ss58Prefix >> 8) | ((ss58Prefix & 0x0003) << 6);
        return new byte[]{(byte) first, (byte) second};
    }
    private Ed25519KeyProvider provider() {
        return testProvider != null ? testProvider : keyMaterial.ed25519();
    }
}

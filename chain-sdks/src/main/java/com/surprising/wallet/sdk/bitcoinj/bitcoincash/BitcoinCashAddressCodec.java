package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bitcoin Cash CashAddr地址编解码器，支持P2PKH和P2SH地址的编码与解码。
 *
 * <h3>CashAddr格式</h3>
 * CashAddr是Bitcoin Cash（BCH）在2018年引入的新地址格式，旨在解决传统Base58地址
 * 容易与BTC地址混淆的问题。格式为：{@code prefix:payload}，其中：
 * <ul>
 *   <li><b>prefix</b>：人类可读前缀，主网为{@code bitcoincash}，测试网为{@code bchtest}</li>
 *   <li><b>payload</b>：Base32编码的数据，包含版本字节和20字节哈希（RIPEMD160）</li>
 * </ul>
 *
 * <h3>编码流程</h3>
 * <ol>
 *   <li>构建payload：版本字节(类型&lt;&lt;3) + 20字节哈希</li>
 *   <li>8位到5位转换（convertBits）</li>
 *   <li>前缀扩展并计算BCH多项式校验和</li>
 *   <li>拼接前缀:Base32(payload + checksum)</li>
 * </ol>
 *
 * <h3>与Base58Check的区别</h3>
 * <ul>
 *   <li>Base58Check: 双重SHA-256校验和 + Base58编码</li>
 *   <li>CashAddr: BCH多项式校验和 + Base32编码，更强的错误检测能力</li>
 * </ul>
 */
public final class BitcoinCashAddressCodec {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final long[] GENERATORS = {
            0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L,
            0xae2eabe2a8L, 0x1e4f43e470L
    };

    private BitcoinCashAddressCodec() {
    }

    public static String fromLegacy(LegacyAddress address, String prefix) {
        return encode(prefix, address.p2sh ? 1 : 0, address.getHash());
    }

    public static LegacyAddress toLegacy(
            NetworkParameters params, String expectedPrefix, String cashAddress) {
        Decoded decoded = decode(expectedPrefix, cashAddress);
        return decoded.type == 0
                ? LegacyAddress.fromPubKeyHash(params, decoded.hash)
                : LegacyAddress.fromScriptHash(params, decoded.hash);
    }

    public static String encode(String prefix, int type, byte[] hash) {
        if (type < 0 || type > 1 || hash == null || hash.length != 20) {
            throw new IllegalArgumentException("only 160-bit P2PKH/P2SH CashAddr is supported");
        }
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        byte version = (byte) (type << 3);
        byte[] payload8 = new byte[hash.length + 1];
        payload8[0] = version;
        System.arraycopy(hash, 0, payload8, 1, hash.length);
        byte[] payload5 = convertBits(payload8, 8, 5, true);
        byte[] checksum = createChecksum(normalizedPrefix, payload5);
        StringBuilder result = new StringBuilder(normalizedPrefix).append(':');
        for (byte value : payload5) {
            result.append(CHARSET.charAt(value));
        }
        for (byte value : checksum) {
            result.append(CHARSET.charAt(value));
        }
        return result.toString();
    }

    public static Decoded decode(String expectedPrefix, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CashAddr is blank");
        }
        boolean lower = value.equals(value.toLowerCase(Locale.ROOT));
        boolean upper = value.equals(value.toUpperCase(Locale.ROOT));
        if (!lower && !upper) {
            throw new IllegalArgumentException("mixed-case CashAddr");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        String prefix = separator >= 0 ? normalized.substring(0, separator)
                : expectedPrefix.toLowerCase(Locale.ROOT);
        String payloadText = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (!prefix.equals(expectedPrefix.toLowerCase(Locale.ROOT)) || payloadText.length() < 8) {
            throw new IllegalArgumentException("wrong CashAddr prefix or payload");
        }
        byte[] payload = new byte[payloadText.length()];
        for (int i = 0; i < payloadText.length(); i++) {
            int index = CHARSET.indexOf(payloadText.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("invalid CashAddr character");
            }
            payload[i] = (byte) index;
        }
        if (polymod(concat(prefixExpand(prefix), payload)) != 0) {
            throw new IllegalArgumentException("invalid CashAddr checksum");
        }
        byte[] data = Arrays.copyOf(payload, payload.length - 8);
        byte[] decoded = convertBits(data, 5, 8, false);
        if (decoded.length != 21) {
            throw new IllegalArgumentException("unsupported CashAddr hash size");
        }
        int version = decoded[0] & 0xff;
        if ((version & 0x80) != 0 || (version & 7) != 0) {
            throw new IllegalArgumentException("unsupported CashAddr version");
        }
        int type = (version >>> 3) & 0x1f;
        if (type > 1) {
            throw new IllegalArgumentException("unsupported CashAddr type");
        }
        return new Decoded(prefix, type, Arrays.copyOfRange(decoded, 1, decoded.length));
    }

    private static byte[] createChecksum(String prefix, byte[] payload) {
        byte[] values = concat(prefixExpand(prefix), payload, new byte[8]);
        long mod = polymod(values);
        byte[] result = new byte[8];
        for (int i = 0; i < 8; i++) {
            result[i] = (byte) ((mod >>> (5 * (7 - i))) & 31);
        }
        return result;
    }

    private static long polymod(byte[] values) {
        long checksum = 1;
        for (byte value : values) {
            long top = checksum >>> 35;
            checksum = ((checksum & 0x07ffffffffL) << 5) ^ (value & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) {
                    checksum ^= GENERATORS[i];
                }
            }
        }
        return checksum ^ 1;
    }

    private static byte[] prefixExpand(String prefix) {
        byte[] result = new byte[prefix.length() + 1];
        for (int i = 0; i < prefix.length(); i++) {
            result[i] = (byte) (prefix.charAt(i) & 0x1f);
        }
        return result;
    }

    private static byte[] convertBits(byte[] input, int fromBits, int toBits, boolean pad) {
        int accumulator = 0;
        int bits = 0;
        int maxValue = (1 << toBits) - 1;
        List<Byte> output = new ArrayList<>();
        for (byte raw : input) {
            int value = raw & 0xff;
            if ((value >>> fromBits) != 0) {
                throw new IllegalArgumentException("invalid bit group");
            }
            accumulator = (accumulator << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                output.add((byte) ((accumulator >>> bits) & maxValue));
            }
        }
        if (pad) {
            if (bits > 0) {
                output.add((byte) ((accumulator << (toBits - bits)) & maxValue));
            }
        } else if (bits >= fromBits || ((accumulator << (toBits - bits)) & maxValue) != 0) {
            throw new IllegalArgumentException("invalid CashAddr padding");
        }
        byte[] result = new byte[output.size()];
        for (int i = 0; i < output.size(); i++) {
            result[i] = output.get(i);
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(array -> array.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    public record Decoded(String prefix, int type, byte[] hash) {
    }
}

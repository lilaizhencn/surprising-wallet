package com.surprising.wallet.chain.aptos;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
/**
 * Aptos BCS（Binary Canonical Serialization）序列化工具。
 *
 * <p>提供 Aptos 交易的 BCS 二进制编码，包括 EntryFunction payload、
 * ULEB128 变长整数编码和 U64/U8 序列化。用于本地构造交易字节后通过
 * {@link AptosTransactionSigner} 签名。
 *
 * <p>所有方法均为静态工具方法，不可实例化。
 */
final class AptosBcs {
    private AptosBcs() {    }

    static byte[] rawEntryFunctionTransaction(String sender, long sequenceNumber,
                                              EntryFunctionPayload payload,
                                              long maxGasAmount, long gasUnitPrice,
                                              long expirationTimestampSecs, int chainId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, AptosHex.addressBytes(sender));
        writeU64(out, sequenceNumber);
        writeUleb128(out, 2); // TransactionPayload::EntryFunction
        writeEntryFunction(out, payload);
        writeU64(out, maxGasAmount);
        writeU64(out, gasUnitPrice);
        writeU64(out, expirationTimestampSecs);
        out.write(chainId & 0xff);
        return out.toByteArray();
    }
    static byte[] addressArg(String address) {
        return AptosHex.addressBytes(address);
    }
    static byte[] u64Arg(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU64(out, value);
        return out.toByteArray();
    }
    static byte[] u8VectorArg(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytesVector(out, value);
        return out.toByteArray();
    }
    static byte[] u8VectorVectorArg(List<byte[]> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVector(out, values, AptosBcs::writeBytesVector);
        return out.toByteArray();
    }
    private static void writeEntryFunction(ByteArrayOutputStream out, EntryFunctionPayload payload) {
        String[] moduleParts = payload.module().split("::");
        if (moduleParts.length != 2) {
            throw new IllegalArgumentException("module must be <address>::<module>");
        }
        writeBytes(out, AptosHex.addressBytes(moduleParts[0]));
        writeString(out, moduleParts[1]);
        writeString(out, payload.function());
        writeVector(out, payload.typeArguments(), AptosBcs::writeTypeTag);
        writeVector(out, payload.arguments(), AptosBcs::writeBytesVector);
    }
    private static void writeTypeTag(ByteArrayOutputStream out, String typeTag) {
        String[] parts = typeTag.split("::");
        if (parts.length != 3) {
            throw new IllegalArgumentException("only struct type tags are supported: " + typeTag);
        }
        writeUleb128(out, 7); // TypeTag::Struct
        writeBytes(out, AptosHex.addressBytes(parts[0]));
        writeString(out, parts[1]);
        writeString(out, parts[2]);
        writeUleb128(out, 0); // generic type params
    }
    private static void writeBytesVector(ByteArrayOutputStream out, byte[] bytes) {
        writeUleb128(out, bytes.length);
        writeBytes(out, bytes);
    }
    private static <T> void writeVector(ByteArrayOutputStream out, List<T> values, Writer<T> writer) {
        writeUleb128(out, values.size());
        for (T value : values) {
            writer.write(out, value);
        }
    }
    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUleb128(out, bytes.length);
        writeBytes(out, bytes);
    }
    private static void writeU64(ByteArrayOutputStream out, long value) {
        writeBytes(out, ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }
    private static void writeUleb128(ByteArrayOutputStream out, int value) {
        int remaining = value;
        do {
            int byteValue = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining != 0) {
                byteValue |= 0x80;
            }
            out.write(byteValue);
        } while (remaining != 0);
    }
    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(ByteArrayOutputStream out, T value);
    }

    record EntryFunctionPayload(String module, String function,
                                List<String> typeArguments, List<byte[]> arguments) {
    }
}

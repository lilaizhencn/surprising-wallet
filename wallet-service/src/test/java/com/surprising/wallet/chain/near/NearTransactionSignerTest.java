package com.surprising.wallet.chain.near;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearTransactionSignerTest {

    @Test
    void serializesTransferTransactionWithBorshLayout() {
        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) 7);
        byte[] blockHash = new byte[32];
        Arrays.fill(blockHash, (byte) 9);

        byte[] tx = NearTransactionSigner.transactionBytes(
                "alice.testnet",
                publicKey,
                42L,
                "bob.testnet",
                blockHash,
                new BigInteger("1000000000000000000000000"));

        assertEquals("alice.testnet".length(), littleEndianU32(tx, 0));
        assertEquals('a', tx[4]);
        int publicKeyOffset = 4 + "alice.testnet".length();
        assertEquals(0, tx[publicKeyOffset]);
        assertEquals(7, tx[publicKeyOffset + 1]);
        int nonceOffset = publicKeyOffset + 1 + 32;
        assertEquals(42L, littleEndianU64(tx, nonceOffset));
        int actionsOffset = nonceOffset + 8 + 4 + "bob.testnet".length() + 32;
        assertEquals(1, littleEndianU32(tx, actionsOffset));
        assertEquals(3, tx[actionsOffset + 4]);
    }

    @Test
    void serializesFunctionCallTransactionWithBorshLayout() {
        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) 3);
        byte[] blockHash = new byte[32];
        Arrays.fill(blockHash, (byte) 4);
        byte[] args = "{\"receiver_id\":\"bob.testnet\",\"amount\":\"10\"}".getBytes();

        byte[] tx = NearTransactionSigner.functionCallTransactionBytes(
                "alice.testnet",
                publicKey,
                8L,
                "token.testnet",
                blockHash,
                "ft_transfer",
                args,
                30_000_000_000_000L,
                BigInteger.ONE);

        int publicKeyOffset = 4 + "alice.testnet".length();
        int nonceOffset = publicKeyOffset + 1 + 32;
        int actionsOffset = nonceOffset + 8 + 4 + "token.testnet".length() + 32;
        assertEquals(1, littleEndianU32(tx, actionsOffset));
        assertEquals(2, tx[actionsOffset + 4]);
        int methodOffset = actionsOffset + 5;
        assertEquals("ft_transfer".length(), littleEndianU32(tx, methodOffset));
        int argsOffset = methodOffset + 4 + "ft_transfer".length();
        assertEquals(args.length, littleEndianU32(tx, argsOffset));
        int gasOffset = argsOffset + 4 + args.length;
        assertEquals(30_000_000_000_000L, littleEndianU64(tx, gasOffset));
        assertEquals(1, tx[gasOffset + 8]);
    }

    @Test
    void serializesDeployContractAndInitWithBorshLayout() {
        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) 5);
        byte[] blockHash = new byte[32];
        Arrays.fill(blockHash, (byte) 6);
        byte[] wasm = new byte[]{0, 97, 115, 109};
        byte[] args = "{\"owner_id\":\"alice.testnet\"}".getBytes();

        byte[] tx = NearTransactionSigner.deployContractAndFunctionCallTransactionBytes(
                "alice.testnet",
                publicKey,
                9L,
                "alice.testnet",
                blockHash,
                wasm,
                "init",
                args,
                200_000_000_000_000L,
                BigInteger.ZERO);

        int publicKeyOffset = 4 + "alice.testnet".length();
        int nonceOffset = publicKeyOffset + 1 + 32;
        int actionsOffset = nonceOffset + 8 + 4 + "alice.testnet".length() + 32;
        assertEquals(2, littleEndianU32(tx, actionsOffset));
        assertEquals(1, tx[actionsOffset + 4]);
        int wasmLengthOffset = actionsOffset + 5;
        assertEquals(wasm.length, littleEndianU32(tx, wasmLengthOffset));
        int functionActionOffset = wasmLengthOffset + 4 + wasm.length;
        assertEquals(2, tx[functionActionOffset]);
        assertEquals("init".length(), littleEndianU32(tx, functionActionOffset + 1));
    }

    @Test
    void signsAndEncodesSignedTransaction() {
        NearKeyService keyService = new NearKeyService("000102030405060708090a0b0c0d0e0f");
        NearTransactionSigner signer = new NearTransactionSigner(keyService);

        NearTransactionSigner.SignedTransaction signed = signer.transfer(
                1L, 0, 0,
                keyService.address(1, 0, 0),
                1L,
                keyService.address(2, 0, 0),
                "11111111111111111111111111111111",
                BigInteger.ONE);

        assertFalse(signed.transactionHash().isBlank());
        assertFalse(signed.publicKeyBase58().isBlank());
        assertTrue(Base64.getDecoder().decode(signed.signedTransactionBase64()).length > 65);
    }

    private static int littleEndianU32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static long littleEndianU64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }
}

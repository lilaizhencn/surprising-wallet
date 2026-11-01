package com.surprising.wallet.sdk.signing;

import java.math.BigInteger;
import java.util.List;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;

class MpcEvmTransactionCodecTest {
    private static final long CHAIN = 31337;
    private static final BigInteger PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger GAS = BigInteger.valueOf(21000);
    private static final BigInteger ORDER = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").getN();
    private static final String TO = "0x" + "ab".repeat(20);

    @Test void legacyAccessListDynamicAnd7702PreserveFieldsAndRecoverTheJointSigner() throws Exception {
        var fixture = new ECKey();
        String expected = "0x" + org.web3j.crypto.Keys.getAddress(Sign.publicFromPoint(fixture.decompress().getPubKey()));
        for (var raw : transactions()) {
            byte[] digest = MpcEvmTransactionCodec.digest(raw, CHAIN);
            byte[] payload = MpcEvmTransactionCodec.unsignedPayload(raw, CHAIN);
            var restored = MpcEvmTransactionCodec.restore(payload, CHAIN);
            assertArrayEquals(payload, MpcEvmTransactionCodec.unsignedPayload(restored, CHAIN));
            assertArrayEquals(digest, MpcEvmTransactionCodec.digest(restored, CHAIN));
            byte[] signature = fixture.sign(org.bitcoinj.base.Sha256Hash.wrap(digest)).encodeToDER();
            byte[] signed = MpcEvmTransactionCodec.assemble(payload, CHAIN, fixture.getPubKey(), signature);
            var decoded = (SignedRawTransaction) TransactionDecoder.decode(Numeric.toHexString(signed));
            assertEquals(expected, decoded.getFrom());
            if (decoded.getTransaction() instanceof org.web3j.crypto.transaction.type.Transaction2930 typed) {
                assertEquals(CHAIN, typed.getChainId());
            } else assertEquals(Long.valueOf(CHAIN), decoded.getChainId());
            assertEquals(raw.getNonce(), decoded.getNonce());
            assertEquals(raw.getValue(), decoded.getValue());
            assertEquals(raw.getGasLimit(), decoded.getGasLimit());
            assertEquals(raw.getTo(), decoded.getTo());
            assertEquals(raw.getData(), decoded.getData());
            assertEquals(raw.getType(), decoded.getType());
            assertTrue(Numeric.toBigInt(decoded.getSignatureData().getS()).compareTo(ORDER.shiftRight(1)) <= 0);
            assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.assemble(raw, CHAIN,
                    new ECKey().getPubKey(), signature));
            assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.assemble(raw, CHAIN + 1,
                    fixture.getPubKey(), signature));
            assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.restore(signed, CHAIN));
            assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.restore(payload, CHAIN + 1));
        }
    }

    @Test void highSIsCanonicalizedAndAnAlteredTransactionIsRejected() {
        var key = new ECKey();
        var raw = transactions().getFirst();
        var signature = key.sign(org.bitcoinj.base.Sha256Hash.wrap(MpcEvmTransactionCodec.digest(raw, CHAIN)));
        byte[] highS = new ECKey.ECDSASignature(signature.r, ORDER.subtract(signature.s)).encodeToDER();
        assertArrayEquals(MpcEvmTransactionCodec.assemble(raw, CHAIN, key.getPubKey(), signature.encodeToDER()),
                MpcEvmTransactionCodec.assemble(raw, CHAIN, key.getPubKey(), highS));
        var altered = RawTransaction.createEtherTransaction(BigInteger.TWO, PRICE, GAS, TO, BigInteger.ONE);
        assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.assemble(altered, CHAIN, key.getPubKey(), highS));
        assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.unsignedPayload(raw, 0));
        assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.assemble(raw, CHAIN, key.getPubKey(), new byte[64]));
    }

    private static List<RawTransaction> transactions() {
        return List.of(
                RawTransaction.createEtherTransaction(BigInteger.ONE, PRICE, GAS, TO, BigInteger.ONE),
                RawTransaction.createTransaction(CHAIN, BigInteger.ONE, PRICE, GAS, TO, BigInteger.ONE, "", List.of()),
                RawTransaction.createEtherTransaction(CHAIN, BigInteger.ONE, GAS, TO, BigInteger.ONE, PRICE, PRICE),
                RawTransaction.createTransaction(CHAIN, BigInteger.ONE, PRICE, PRICE, GAS, TO, BigInteger.ONE, "", List.of(), List.of()));
    }
}

package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.TransactionBroadcastValidator;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegwitP2wshMultisigTest {
    private static final NetworkParameters PARAMS = TestNet3Params.get();
    private static final HexFormat HEX = HexFormat.of();
    private static final ECKey KEY_1 = ECKey.fromPrivate(BigInteger.valueOf(2), true);
    private static final ECKey KEY_2 = ECKey.fromPrivate(BigInteger.valueOf(3), true);
    private static final ECKey KEY_3 = ECKey.fromPrivate(BigInteger.valueOf(4), true);
    private static final Coin INPUT_VALUE = Coin.valueOf(150_000L);

    @Test
    void nativeP2wshAddressAndWitnessScriptAreGenerated() throws Exception {
        SegwitMultiSignAddressGenerator generator = multisigGenerator();

        String address = generator.generateAddress(PARAMS, 2);
        Address parsed = Address.fromString(PARAMS, address);

        assertTrue(address.startsWith("tb1"));
        assertEquals(ScriptType.P2WSH, parsed.getOutputScriptType());
        assertEquals(2, generator.getMinSignNum());
        assertEquals(3, generator.getMaxSignNum());
        assertEquals(32, generator.getWitnessProgram().length);
        assertEquals(generator.getWitnessScript().program().length * 2, generator.getWitnessScriptStr().length());
    }

    @Test
    void twoOfThreeFirstAndSecondSignProduceBroadcastReadyNativeSegwitTransaction() throws Exception {
        SegwitMultiSignAddressGenerator generator = multisigGenerator();
        generator.generateAddress(PARAMS, 2);
        String witnessScriptHex = generator.getWitnessScriptStr();

        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);
        builder.addInput("0101010101010101010101010101010101010101010101010101010101010101", 0,
                witnessScriptHex, INPUT_VALUE);
        builder.addOutput(SegwitAddress.fromKey(PARAMS, ECKey.fromPrivate(BigInteger.valueOf(11), true)).toBech32(),
                Coin.valueOf(80_000L));
        builder.addOutput(SegwitAddress.fromKey(PARAMS, ECKey.fromPrivate(BigInteger.valueOf(12), true)).toBech32(),
                Coin.valueOf(40_000L));

        String firstHex = builder.buildFirstSign(List.of(KEY_1));
        String fullHex = builder.buildSecondSign(firstHex, List.of(KEY_2), List.of(witnessScriptHex), List.of(INPUT_VALUE));
        Transaction firstTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(firstHex)));
        Transaction fullTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fullHex)));

        assertEquals(firstTx.getTxId(), fullTx.getTxId(), "SegWit txid must not change when witness changes");
        assertNotEquals(firstTx.getWTxId(), fullTx.getWTxId(), "wtxid should reflect the second signature");
        assertEquals(0, fullTx.getInput(0).getScriptSig().program().length, "native P2WSH scriptSig must be empty");

        TransactionWitness witness = fullTx.getInput(0).getWitness();
        assertEquals(4, witness.getPushCount());
        assertEquals(0, witness.getPush(0).length);
        assertFalse(java.util.Arrays.equals(witness.getPush(1), witness.getPush(2)));
        assertEquals(generator.getWitnessScriptStr(), HEX.formatHex(witness.getPush(3)));

        Script witnessScript = new Script(witness.getPush(3));
        TransactionSignature sig1 = TransactionSignature.decodeFromBitcoin(witness.getPush(1), true, true);
        TransactionSignature sig2 = TransactionSignature.decodeFromBitcoin(witness.getPush(2), true, true);
        Sha256Hash hash1 = fullTx.hashForWitnessSignature(0, witnessScript, INPUT_VALUE,
                sig1.sigHashMode(), sig1.anyoneCanPay());
        Sha256Hash hash2 = fullTx.hashForWitnessSignature(0, witnessScript, INPUT_VALUE,
                sig2.sigHashMode(), sig2.anyoneCanPay());
        assertTrue(KEY_1.verify(hash1, sig1));
        assertTrue(KEY_2.verify(hash2, sig2));

        TransactionBroadcastValidator.ValidationResult validation =
                new TransactionBroadcastValidator(PARAMS).validate(fullHex);
        assertTrue(validation.valid, validation.errors.toString());
        assertEquals(1, validation.inputCount);
        assertEquals(2, validation.signatureCount);
    }

    @Test
    void segwitVbytesAreLowerThanLegacyMultisigByteEstimate() {
        long legacyBytes = 325L + 35L * 2L + 15L;
        long estimatedSegwitVbytes = WitnessTransactionBuilder.estimateVBytes(1, 2);

        assertTrue(estimatedSegwitVbytes < legacyBytes);
        assertTrue(estimatedSegwitVbytes <= legacyBytes * 60 / 100);
    }

    @Test
    void mockP2wshBlockScanIsIdempotentForUtxoAddAndSpend() {
        SegwitMultiSignAddressGenerator generator = multisigGenerator();
        String watchedAddress = generator.generateAddress(PARAMS, 2);
        Map<String, Coin> utxos = new HashMap<>();

        Transaction funding = Transaction.coinbase();
        funding.addOutput(INPUT_VALUE, Address.fromString(PARAMS, watchedAddress));
        scanOutputs(funding, watchedAddress, utxos);
        scanOutputs(funding, watchedAddress, utxos);
        assertEquals(1, utxos.size(), "duplicate block scans must not duplicate UTXOs");

        Transaction spend = new Transaction(PARAMS);
        spend.addInput(funding.getTxId(), 0, ScriptBuilder.createEmpty());
        spend.addOutput(Coin.valueOf(50_000L),
                SegwitAddress.fromKey(PARAMS, ECKey.fromPrivate(BigInteger.valueOf(13), true)));
        applySpends(spend, utxos);
        applySpends(spend, utxos);
        assertTrue(utxos.isEmpty(), "duplicate spend scans must remain idempotent");
    }

    @Test
    void p2wshScriptVerificationSurfaceIsExplicitlyCoveredByManualSignatureChecks() {
        assertDoesNotThrow(this::twoOfThreeFirstAndSecondSignProduceBroadcastReadyNativeSegwitTransaction);
    }

    private static SegwitMultiSignAddressGenerator multisigGenerator() {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(KEY_1);
        generator.addECKey(KEY_2);
        generator.addECKey(KEY_3);
        return generator;
    }

    private static void scanOutputs(Transaction tx, String watchedAddress, Map<String, Coin> utxos) {
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            Address address = tx.getOutput(i).getScriptPubKey().getToAddress(PARAMS);
            if (watchedAddress.equals(address.toString())) {
                utxos.putIfAbsent(tx.getTxId() + ":" + i, tx.getOutput(i).getValue());
            }
        }
    }

    private static void applySpends(Transaction tx, Map<String, Coin> utxos) {
        tx.getInputs().forEach(input -> utxos.remove(input.getOutpoint().hash() + ":" + input.getOutpoint().index()));
    }
}

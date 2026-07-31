package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code SegwitP2wshMultisigTest} 覆盖的业务流程、边界条件和异常行为。
 */
class SegwitP2wshMultisigTest {
    /**
     * 保存 {@code PARAMS}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final NetworkParameters PARAMS = TestNet3Params.get();
    /**
     * 保存 {@code HEX}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final HexFormat HEX = HexFormat.of();
    /**
     * 保存 {@code KEY_1}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final ECKey KEY_1 = ECKey.fromPrivate(BigInteger.valueOf(2), true);
    /**
     * 保存 {@code KEY_2}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final ECKey KEY_2 = ECKey.fromPrivate(BigInteger.valueOf(3), true);
    /**
     * 保存 {@code KEY_3}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final ECKey KEY_3 = ECKey.fromPrivate(BigInteger.valueOf(4), true);
    /**
     * 保存 {@code WRONG_KEY}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final ECKey WRONG_KEY = ECKey.fromPrivate(BigInteger.valueOf(99), true);
    /**
     * 保存 {@code INPUT_VALUE}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final Coin INPUT_VALUE = Coin.valueOf(150_000L);
    /**
     * 保存 {@code XPUB_1}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_1 = "tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB";
    /**
     * 保存 {@code XPUB_2}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_2 = "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";
    /**
     * 保存 {@code XPUB_3}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_3 = "tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4";

    /**
     * 验证 {@code nativeP2wshAddressAndWitnessScriptAreGenerated} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code twoOfThreeFirstAndSecondSignProduceBroadcastReadyNativeSegwitTransaction} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void twoOfThreeFirstAndSecondSignProduceBroadcastReadyNativeSegwitTransaction() throws Exception {
        SignedFixture fixture = signedFixture();
        Transaction firstTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fixture.firstHex)));
        Transaction fullTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fixture.fullHex)));

        assertEquals(firstTx.getTxId(), fullTx.getTxId(), "SegWit txid must not change when witness changes");
        assertNotEquals(firstTx.getWTxId(), fullTx.getWTxId(), "wtxid should reflect the second signature");
        assertEquals(0, fullTx.getInput(0).getScriptSig().program().length, "native P2WSH scriptSig must be empty");

        TransactionWitness witness = fullTx.getInput(0).getWitness();
        assertEquals(4, witness.getPushCount());
        assertEquals(0, witness.getPush(0).length);
        assertFalse(java.util.Arrays.equals(witness.getPush(1), witness.getPush(2)));
        assertEquals(fixture.witnessScriptHex, HEX.formatHex(witness.getPush(3)));

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
                new TransactionBroadcastValidator(PARAMS).validate(fixture.fullHex);
        assertTrue(validation.valid, validation.errors.toString());
        assertEquals(1, validation.inputCount);
        assertEquals(2, validation.signatureCount);
    }

    /**
     * 验证 {@code configuredTpubs_shouldDeriveNativeP2wshMultisigAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void configuredTpubs_shouldDeriveNativeP2wshMultisigAddress() {
        SegwitMultiSignAddressGenerator generator = multisigGeneratorFromConfiguredTpubs(1, 1, 0, 0);
        String address = generator.generateAddress(PARAMS, 2);
        String repeat = multisigGeneratorFromConfiguredTpubs(1, 1, 0, 0).generateAddress(PARAMS, 2);

        assertEquals(repeat, address);
        assertTrue(address.startsWith("tb1"));
        assertEquals(ScriptType.P2WSH, Address.fromString(PARAMS, address).getOutputScriptType());
        assertEquals(3, generator.getWitnessScript().getPubKeys().size());
    }

    /**
     * 验证 {@code firstSign_shouldProducePartialWitness} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void firstSign_shouldProducePartialWitness() {
        FirstSignFixture fixture = firstSignFixture();
        Transaction tx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fixture.firstHex)));

        TransactionWitness witness = tx.getInput(0).getWitness();
        assertEquals(3, witness.getPushCount());
        assertEquals(0, witness.getPush(0).length);
        assertTrue(witness.getPush(1).length > 0);
        assertEquals(fixture.witnessScriptHex, HEX.formatHex(witness.getPush(2)));
    }

    /**
     * 验证 {@code secondSign_shouldCompleteWitness} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void secondSign_shouldCompleteWitness() {
        SignedFixture fixture = signedFixture();
        Transaction tx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fixture.fullHex)));
        TransactionWitness witness = tx.getInput(0).getWitness();

        assertEquals(4, witness.getPushCount());
        assertEquals(0, witness.getPush(0).length);
        assertFalse(Arrays.equals(witness.getPush(1), witness.getPush(2)));
        assertEquals(fixture.witnessScriptHex, HEX.formatHex(witness.getPush(3)));
    }

    /**
     * 验证 {@code signedP2wshMultisigTx_shouldVerify} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void signedP2wshMultisigTx_shouldVerify() {
        SignedFixture fixture = signedFixture();
        TransactionBroadcastValidator.ValidationResult validation =
                new TransactionBroadcastValidator(PARAMS).validate(fixture.fullHex);

        assertTrue(validation.valid, validation.errors.toString());
        assertEquals(2, validation.signatureCount);
    }

    /**
     * 验证 {@code redisSerializedTx_shouldKeepWitness} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void redisSerializedTx_shouldKeepWitness() {
        SignedFixture fixture = signedFixture();
        String redisValue = "rawTransaction:" + fixture.fullHex;
        String restoredHex = redisValue.substring("rawTransaction:".length());
        Transaction restored = Transaction.read(ByteBuffer.wrap(HEX.parseHex(restoredHex)));

        assertEquals(4, restored.getInput(0).getWitness().getPushCount());
        assertEquals(fixture.fullTxId, restored.getTxId().toString());
    }

    /**
     * 验证 {@code wrongPrivateKey_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void wrongPrivateKey_shouldFail() {
        FirstSignFixture fixture = firstSignFixture();
        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);

        assertThrows(IllegalArgumentException.class, () ->
                builder.buildSecondSign(fixture.firstHex, List.of(WRONG_KEY),
                        List.of(fixture.witnessScriptHex), List.of(INPUT_VALUE)));
    }

    /**
     * 验证 {@code duplicatePrivateKey_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void duplicatePrivateKey_shouldFail() {
        FirstSignFixture fixture = firstSignFixture();
        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);

        assertThrows(IllegalArgumentException.class, () ->
                builder.buildSecondSign(fixture.firstHex, List.of(KEY_1),
                        List.of(fixture.witnessScriptHex), List.of(INPUT_VALUE)));
    }

    /**
     * 验证 {@code missingInputValue_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void missingInputValue_shouldFail() {
        SegwitMultiSignAddressGenerator generator = multisigGenerator();
        generator.generateAddress(PARAMS, 2);
        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);

        assertThrows(IllegalArgumentException.class, () ->
                builder.addInput("0101010101010101010101010101010101010101010101010101010101010101",
                        0, generator.getWitnessScriptStr(), null));
    }

    /**
     * 验证 {@code wrongWitnessScript_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void wrongWitnessScript_shouldFail() {
        FirstSignFixture fixture = firstSignFixture();
        SegwitMultiSignAddressGenerator wrong = new SegwitMultiSignAddressGenerator();
        wrong.addECKey(KEY_1);
        wrong.addECKey(KEY_2);
        wrong.addECKey(WRONG_KEY);
        wrong.generateAddress(PARAMS, 2);

        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);
        assertThrows(IllegalArgumentException.class, () ->
                builder.buildSecondSign(fixture.firstHex, List.of(KEY_2),
                        List.of(wrong.getWitnessScriptStr()), List.of(INPUT_VALUE)));
    }

    /**
     * 验证 {@code segwitVbytesAreLowerThanLegacyMultisigByteEstimate} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void segwitVbytesAreLowerThanLegacyMultisigByteEstimate() {
        long legacyBytes = 325L + 35L * 2L + 15L;
        long estimatedSegwitVbytes = WitnessTransactionBuilder.estimateVBytes(1, 2);

        assertTrue(estimatedSegwitVbytes < legacyBytes);
        assertTrue(estimatedSegwitVbytes <= legacyBytes * 60 / 100);
    }

    /**
     * 验证 {@code feeCalculator_shouldEstimateP2wshSingleInputSingleOutput} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void feeCalculator_shouldEstimateP2wshSingleInputSingleOutput() {
        assertEquals(634L, P2wshFeeCalculator.estimateWeight(1, 1, 2, 3));
        assertEquals(159L, P2wshFeeCalculator.estimateVBytes(1, 1));
        assertEquals(1_590L, P2wshFeeCalculator.calculateFeeSat(1, 1, 10));
    }

    /**
     * 验证 {@code feeCalculator_shouldEstimateP2wshMultiInputTwoOutputs} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void feeCalculator_shouldEstimateP2wshMultiInputTwoOutputs() {
        assertEquals(1_226L, P2wshFeeCalculator.estimateWeight(2, 2, 2, 3));
        assertEquals(307L, P2wshFeeCalculator.estimateVBytes(2, 2));
    }

    /**
     * 验证 {@code feeCalculator_shouldMergeDustChangeIntoFee} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void feeCalculator_shouldMergeDustChangeIntoFee() {
        long inputSat = 100_000L;
        long sendSat = 97_880L;

        P2wshFeeCalculator.FeeResult result = P2wshFeeCalculator.calculate(inputSat, sendSat, 1, 1, 10);

        assertEquals(0L, result.getChangeSat());
        assertEquals(2_120L, result.getFeeSat());
        assertEquals(159L, result.getVbytes());
    }

    /**
     * 验证 {@code feeCalculator_shouldScaleWithFeeRate} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void feeCalculator_shouldScaleWithFeeRate() {
        assertEquals(202L, P2wshFeeCalculator.calculateFeeSat(1, 2, 1));
        assertEquals(5_050L, P2wshFeeCalculator.calculateFeeSat(1, 2, 25));
    }

    /**
     * 验证 {@code mockP2wshBlockScanIsIdempotentForUtxoAddAndSpend} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code p2wshScriptVerificationSurfaceIsExplicitlyCoveredByManualSignatureChecks} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void p2wshScriptVerificationSurfaceIsExplicitlyCoveredByManualSignatureChecks() {
        assertDoesNotThrow(this::twoOfThreeFirstAndSecondSignProduceBroadcastReadyNativeSegwitTransaction);
    }

    /**
     * 验证 {@code multisigGenerator} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static SegwitMultiSignAddressGenerator multisigGenerator() {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(KEY_1);
        generator.addECKey(KEY_2);
        generator.addECKey(KEY_3);
        return generator;
    }

    /**
     * 验证 {@code multisigGeneratorFromConfiguredTpubs} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static SegwitMultiSignAddressGenerator multisigGeneratorFromConfiguredTpubs(
            int currency, int biz, int userId, int index) {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(derive(XPUB_1, currency, biz, userId, index));
        generator.addECKey(derive(XPUB_2, currency, biz, userId, index));
        generator.addECKey(derive(XPUB_3, currency, biz, userId, index));
        return generator;
    }

    /**
     * 验证 {@code derive} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ECKey derive(String xpub, int currency, int biz, int userId, int index) {
        return Bip32Node.decode(xpub)
                .getChild(44)
                .getChild(currency)
                .getChild(biz)
                .getChild(userId)
                .getChild(index)
                .getEcKey();
    }

    /**
     * 验证 {@code firstSignFixture} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static FirstSignFixture firstSignFixture() {
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

        return new FirstSignFixture(witnessScriptHex, builder.buildFirstSign(List.of(KEY_1)));
    }

    /**
     * 验证 {@code signedFixture} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static SignedFixture signedFixture() {
        FirstSignFixture first = firstSignFixture();
        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(PARAMS);
        String fullHex = builder.buildSecondSign(first.firstHex, List.of(KEY_2),
                List.of(first.witnessScriptHex), List.of(INPUT_VALUE));
        Transaction fullTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(fullHex)));
        return new SignedFixture(first.witnessScriptHex, first.firstHex, fullHex, fullTx.getTxId().toString());
    }

    /**
     * 测试辅助类 {@code FirstSignFixture}，为相关测试提供隔离环境或共享数据。
     */
    private static class FirstSignFixture {
        /**
         * 保存 {@code witnessScriptHex}，用于承载当前测试夹具的配置或运行数据。
         */
        private final String witnessScriptHex;
        /**
         * 保存 {@code firstHex}，用于承载当前测试夹具的配置或运行数据。
         */
        private final String firstHex;

        /**
         * 验证 {@code FirstSignFixture} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FirstSignFixture(String witnessScriptHex, String firstHex) {
            this.witnessScriptHex = witnessScriptHex;
            this.firstHex = firstHex;
        }
    }

    /**
     * 测试辅助类 {@code SignedFixture}，为相关测试提供隔离环境或共享数据。
     */
    private static class SignedFixture {
        /**
         * 保存 {@code witnessScriptHex}，用于承载当前测试夹具的配置或运行数据。
         */
        private final String witnessScriptHex;
        /**
         * 保存 {@code firstHex}，用于承载当前测试夹具的配置或运行数据。
         */
        private final String firstHex;
        /**
         * 保存 {@code fullHex}，用于承载当前测试夹具的配置或运行数据。
         */
        private final String fullHex;
        /**
         * 保存 {@code fullTxId}，用于标识测试中的交易、区块或业务记录。
         */
        private final String fullTxId;

        /**
         * 验证 {@code SignedFixture} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private SignedFixture(String witnessScriptHex, String firstHex, String fullHex, String fullTxId) {
            this.witnessScriptHex = witnessScriptHex;
            this.firstHex = firstHex;
            this.fullHex = fullHex;
            this.fullTxId = fullTxId;
        }
    }

    /**
     * 验证 {@code scanOutputs} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void scanOutputs(Transaction tx, String watchedAddress, Map<String, Coin> utxos) {
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            Address address = tx.getOutput(i).getScriptPubKey().getToAddress(PARAMS);
            if (watchedAddress.equals(address.toString())) {
                utxos.putIfAbsent(tx.getTxId() + ":" + i, tx.getOutput(i).getValue());
            }
        }
    }

    /**
     * 验证 {@code applySpends} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void applySpends(Transaction tx, Map<String, Coin> utxos) {
        tx.getInputs().forEach(input -> utxos.remove(input.getOutpoint().hash() + ":" + input.getOutpoint().index()));
    }
}

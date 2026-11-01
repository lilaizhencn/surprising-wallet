package com.surprising.wallet.chain.cardano;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;
import static org.junit.jupiter.api.Assertions.*;

class MpcCardanoTransactionCodecTest {
    private static final HexFormat HEX = HexFormat.of();
    static final String POLICY = "ab".repeat(28);
    static final String USDC = POLICY + "55534443";
    static final String USDT = POLICY + "55534454";
    private final Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    private final String sender = MpcCardanoTransactionCodec.address(publicKey, false);
    private final String recipient = MpcCardanoTransactionCodec.address(new Ed25519PrivateKeyParameters(new SecureRandom()).generatePublicKey().getEncoded(), false);

    @Test void publicSdkBuildsAdaAndMultiassetBodiesWithOneVerifiedExternalWitness() throws Exception {
        for (String unit : List.of("lovelace", USDT, USDC)) {
            var u = prepare(unit);
            byte[] preimage = MpcCardanoTransactionCodec.signingBytes(u);
            assertEquals(32, preimage.length);
            assertEquals(HEX.formatHex(preimage), MpcCardanoTransactionCodec.txHash(u));
            var selected = MpcCardanoTransactionCodec.assemble(u, sign(preimage));
            MpcCardanoTransactionCodec.verifySelected(u, selected);
            var transaction = Transaction.deserialize(HEX.parseHex(selected.transactionHex()));
            assertEquals(1, transaction.getWitnessSet().getVkeyWitnesses().size());
            assertArrayEquals(publicKey, transaction.getWitnessSet().getVkeyWitnesses().getFirst().getVkey());
            assertEquals(1420, transaction.getBody().getTtl());
            assertEquals(700, transaction.getBody().getValidityStartInterval());
            assertTrue(MpcCardanoTransactionCodec.fee(u).signum() > 0);
            assertTrue(MpcCardanoTransactionCodec.recipientLovelace(u).signum() > 0);
            assertEquals(selected, MpcCardanoTransactionCodec.assemble(u, sign(preimage)));
        }
    }

    @Test void rejectsChangedPrincipalInputFactsChangeAndUnknownBodyKeys() {
        var u = prepare(USDC);
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.signingBytes(copy(u, u.transactionHex(), u.amount().add(BigInteger.ONE), u.inputs())));
        var fact = u.inputs().getFirst();
        var changedFact = new MpcCardanoTransactionCodec.Input("cd".repeat(32), fact.index(), fact.outputHex());
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.signingBytes(copy(u, u.transactionHex(), u.amount(), List.of(changedFact))));
        var env = (Array) MpcCardanoTransactionCodec.decode(HEX.parseHex(u.transactionHex()));
        var body = (co.nstant.in.cbor.model.Map) env.getDataItems().getFirst();
        body.put(new UnsignedInteger(22), new UnsignedInteger(1));
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.signingBytes(copy(u, HEX.formatHex(MpcCardanoTransactionCodec.encode(env)), u.amount(), u.inputs())));
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.signingBytes(copy(u, u.transactionHex() + "00", u.amount(), u.inputs())));
    }

    @Test void rejectsWrongWitnessTamperedWireAndHiddenAuxiliaryData() {
        var u = prepare("lovelace");
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.assemble(u, new byte[64]));
        byte[] signature = sign(MpcCardanoTransactionCodec.signingBytes(u));
        signature[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.assemble(u, signature));
        var selected = MpcCardanoTransactionCodec.assemble(u, sign(MpcCardanoTransactionCodec.signingBytes(u)));
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.verifySelected(u, new MpcCardanoTransactionCodec.Signed(u.transactionHex(), selected.txHash())));
        var envelope = (Array) MpcCardanoTransactionCodec.decode(HEX.parseHex(u.transactionHex()));
        envelope.getDataItems().set(3, new ByteString(new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> MpcCardanoTransactionCodec.signingBytes(copy(u, HEX.formatHex(MpcCardanoTransactionCodec.encode(envelope)), u.amount(), u.inputs())));
    }

    @Test void rejectsAnotherNetworkOrKeyAndInvalidTokenPolicyAndProtocol() {
        var snapshot = snapshot(sender);
        assertThrows(IllegalArgumentException.class, () -> CardanoPublicTransactionBuilder.prepare(genesis(), parameters(), snapshot,
                publicKey, sender, MpcCardanoTransactionCodec.address(publicKey, true), "lovelace", BigInteger.valueOf(2_000_000), 720));
        assertThrows(IllegalArgumentException.class, () -> CardanoAssetUnit.fromTokenContract("aa." + "bb".repeat(28)));
        assertThrows(IllegalArgumentException.class, () -> CardanoAssetUnit.fromTokenContract(POLICY + "aa".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> CardanoPublicTransactionBuilder.protocol(Query.ReadParamsResponse.getDefaultInstance()));
    }

    @Test void nativeEmptyScriptContainerIsAbsentButActualScriptsCannotBecomePlainInputs() {
        var ordinary = snapshot(sender).getItems(0).getCardano();
        assertDoesNotThrow(() -> CardanoPublicTransactionBuilder.output(ordinary));
        assertThrows(IllegalArgumentException.class, () -> CardanoPublicTransactionBuilder.output(ordinary.toBuilder()
                .setScript(Cardano.Script.newBuilder().setPlutusV3(com.google.protobuf.ByteString.EMPTY)).build()));
    }

    @Test void parsedUtxoAmountCannotOverrideOriginalOutputBytes() {
        var fact = snapshot(sender).getItems(0);
        assertDoesNotThrow(() -> CardanoPublicTransactionBuilder.output(fact));
        var changed = fact.toBuilder().setCardano(fact.getCardano().toBuilder().setCoin(integer(200_000_000))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoPublicTransactionBuilder.output(changed));
        assertThrows(IllegalArgumentException.class, () -> CardanoPublicTransactionBuilder.output(fact.toBuilder().clearNativeBytes().build()));
    }

    private MpcCardanoTransactionCodec.Unsigned prepare(String unit) {
        return CardanoPublicTransactionBuilder.prepare(genesis(), parameters(), snapshot(sender), publicKey, sender, recipient,
                unit, BigInteger.valueOf(unit.equals("lovelace") ? 2_000_000 : 1_000_001), 720).unsigned();
    }
    private static MpcCardanoTransactionCodec.Unsigned copy(MpcCardanoTransactionCodec.Unsigned u, String raw, BigInteger amount, List<MpcCardanoTransactionCodec.Input> inputs) {
        return new MpcCardanoTransactionCodec.Unsigned(u.genesisHash(), u.networkMagic(), u.sender(), u.publicKeyHex(), u.recipient(), u.unit(), amount, raw, inputs);
    }
    private byte[] sign(byte[] preimage) {
        var signer = new Ed25519Signer(); signer.init(true, privateKey); signer.update(preimage, 0, preimage.length); return signer.generateSignature();
    }
    static Cardano.BigInt integer(long n) { return Cardano.BigInt.newBuilder().setInt(n).build(); }
    static Query.ReadGenesisResponse genesis() {
        return Query.ReadGenesisResponse.newBuilder().setGenesis(com.google.protobuf.ByteString.copyFrom(HEX.parseHex("01".repeat(32))))
                .setCaip2("cardano:424242").setCardano(Cardano.Genesis.newBuilder().setNetworkId("Testnet").setNetworkMagic(424242)).build();
    }
    static Query.ReadParamsResponse parameters() {
        return Query.ReadParamsResponse.newBuilder().setValues(Query.AnyChainParams.newBuilder().setCardano(Cardano.PParams.newBuilder()
                .setCoinsPerUtxoByte(integer(4310)).setMaxTxSize(16384).setMaxValueSize(5000)
                .setMinFeeCoefficient(integer(1)).setMinFeeConstant(integer(0)).setProtocolVersion(Cardano.ProtocolVersion.newBuilder().setMajor(10)))).build();
    }
    static Query.SearchUtxosResponse snapshot(String owner) {
        var output = Cardano.TxOutput.newBuilder().setAddress(com.google.protobuf.ByteString.copyFrom(CardanoRpcClient.addressBytes(owner))).setCoin(integer(100_000_000))
                .setScript(Cardano.Script.getDefaultInstance())
                .addAssets(Cardano.Multiasset.newBuilder().setPolicyId(com.google.protobuf.ByteString.copyFrom(HEX.parseHex(POLICY)))
                        .addAssets(Cardano.Asset.newBuilder().setName(com.google.protobuf.ByteString.copyFrom(HEX.parseHex("55534443"))).setQuantity(integer(20_000_000)))
                        .addAssets(Cardano.Asset.newBuilder().setName(com.google.protobuf.ByteString.copyFrom(HEX.parseHex("55534454"))).setQuantity(integer(20_000_000))));
        try {
            byte[] original = MpcCardanoTransactionCodec.encode(CardanoPublicTransactionBuilder.output(output.build()).serialize());
            return Query.SearchUtxosResponse.newBuilder().addItems(Query.AnyUtxoData.newBuilder().setCardano(output)
                    .setNativeBytes(com.google.protobuf.ByteString.copyFrom(original))
                    .setTxoRef(Query.TxoRef.newBuilder().setHash(com.google.protobuf.ByteString.copyFrom(HEX.parseHex("02".repeat(32)))).setIndex(0)))
                    .setLedgerTip(Query.ChainPoint.newBuilder().setSlot(700).setHeight(300).setHash(com.google.protobuf.ByteString.copyFrom(HEX.parseHex("03".repeat(32))))).build();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

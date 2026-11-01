package com.surprising.wallet.testutil;

import com.google.protobuf.ByteString;
import com.surprising.wallet.chain.cardano.CardanoPublicTransactionBuilder;
import com.surprising.wallet.chain.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.chain.cardano.CardanoRpcClient;
import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.model.mpc.CardanoSigningPayload;
import java.math.BigDecimal;
import java.util.HexFormat;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;

/** Synthetic public snapshots for PG policy tests only; native acceptance reads actual node state. */
public final class CardanoReceiptTestSupport {
    public static final String GENESIS = "11".repeat(32), POLICY = "ab".repeat(28), USDC = POLICY + ".55534443";
    public static final ChainScanPoint POINT = new ChainScanPoint(100, 200, "22".repeat(32));
    private CardanoReceiptTestSupport() { }
    private static Cardano.BigInt integer(long value) { return Cardano.BigInt.newBuilder().setInt(value).build(); }

    public static CardanoSigningPayload draft(String publicKey, String sender, String recipient, String symbol,
                                              BigDecimal amount, BigDecimal fee) {
        var hex = HexFormat.of();
        var genesis = Query.ReadGenesisResponse.newBuilder().setGenesis(ByteString.copyFrom(hex.parseHex(GENESIS)))
                .setCardano(Cardano.Genesis.newBuilder().setNetworkId("Testnet").setNetworkMagic(424242)).build();
        var params = Query.ReadParamsResponse.newBuilder().setValues(Query.AnyChainParams.newBuilder().setCardano(Cardano.PParams.newBuilder()
                .setCoinsPerUtxoByte(integer(4310)).setMaxTxSize(16384).setMaxValueSize(5000)
                .setMinFeeCoefficient(integer(1)).setMinFeeConstant(integer(0)).setProtocolVersion(Cardano.ProtocolVersion.newBuilder().setMajor(10)))).build();
        var output = Cardano.TxOutput.newBuilder().setAddress(ByteString.copyFrom(CardanoRpcClient.addressBytes(sender))).setCoin(integer(100_000_000))
                .addAssets(Cardano.Multiasset.newBuilder().setPolicyId(ByteString.copyFrom(hex.parseHex(POLICY)))
                        .addAssets(Cardano.Asset.newBuilder().setName(ByteString.copyFrom(hex.parseHex("55534443"))).setQuantity(integer(20_000_000)))).build();
        try {
            var snapshot = Query.SearchUtxosResponse.newBuilder().setLedgerTip(Query.ChainPoint.newBuilder().setHeight(POINT.height()).setSlot(POINT.slot())
                    .setHash(ByteString.copyFrom(hex.parseHex(POINT.hash())))).addItems(Query.AnyUtxoData.newBuilder().setCardano(output)
                    .setNativeBytes(ByteString.copyFrom(MpcCardanoTransactionCodec.encode(CardanoPublicTransactionBuilder.output(output).serialize())))
                    .setTxoRef(Query.TxoRef.newBuilder().setHash(ByteString.copyFrom(hex.parseHex("33".repeat(32)))).setIndex(0))).build();
            var prepared = CardanoPublicTransactionBuilder.prepare(genesis, params, snapshot, hex.parseHex(publicKey), sender, recipient,
                    symbol.equals("ADA") ? "lovelace" : MpcCardanoTransactionCodec.assetUnit(USDC), amount.movePointRight(6).toBigIntegerExact(), 720);
            return new CardanoSigningPayload(symbol, amount, fee, 6, 3, POINT, prepared.unsigned());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

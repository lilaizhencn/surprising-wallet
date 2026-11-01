package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.bitcoinj.base.Bech32;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.sync.Sync;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds unsigned Conway key payments from one immutable public UTxO snapshot. */
public final class CardanoPublicTransactionBuilder {
    private static final HexFormat HEX = HexFormat.of();
    private CardanoPublicTransactionBuilder() { }
    public record Prepared(MpcCardanoTransactionCodec.Unsigned unsigned, Sync.BlockRef observed) { }

    public static Prepared prepare(Query.ReadGenesisResponse genesis, Query.ReadParamsResponse parameters,
                                   Query.SearchUtxosResponse snapshot, byte[] publicKey, String sender, String recipient,
                                   String unit, BigInteger amount, long lifetimeSlots) {
        if (!genesis.hasCardano() || genesis.getGenesis().size() != 32 || !snapshot.hasLedgerTip()
                || snapshot.getLedgerTip().getHash().size() != 32 || snapshot.getLedgerTip().getSlot() < 0
                || lifetimeSlots < 30 || lifetimeSlots > 7_200)
            throw new IllegalArgumentException("invalid Cardano network, ledger snapshot or lifetime");
        boolean mainnet = "Mainnet".equalsIgnoreCase(genesis.getCardano().getNetworkId());
        if (!mainnet && !"Testnet".equalsIgnoreCase(genesis.getCardano().getNetworkId()))
            throw new IllegalArgumentException("Cardano native network identifier is absent or unsupported");
        if (!sender.equals(MpcCardanoTransactionCodec.address(publicKey, mainnet))
                || (CardanoRpcClient.addressBytes(recipient)[0] & 15) != (mainnet ? 1 : 0))
            throw new IllegalArgumentException("Cardano payment address and node network differ");
        String normalized = CardanoAssetUnit.LOVELACE.equals(unit) ? unit : CardanoAssetUnit.fromTokenContract(unit);
        ProtocolParams protocol = protocol(parameters);
        Map<String, Query.AnyUtxoData> facts = new HashMap<>();
        List<Utxo> utxos = new ArrayList<>();
        for (var item : snapshot.getItemsList()) {
            if (!item.hasCardano() || item.getTxoRef().getHash().size() != 32 || item.getTxoRef().getIndex() < 0)
                throw new IllegalArgumentException("invalid Cardano snapshot output reference");
            var output = output(item);
            if (!sender.equals(output.getAddress())) throw new IllegalArgumentException("Cardano snapshot includes another owner");
            var hash = HEX.formatHex(item.getTxoRef().getHash().toByteArray());
            String ref = hash + ":" + item.getTxoRef().getIndex();
            if (facts.put(ref, item) != null) throw new IllegalArgumentException("duplicate Cardano snapshot output");
            var amounts = new ArrayList<Amount>();
            amounts.add(Amount.lovelace(output.getValue().getCoin()));
            for (var multi : output.getValue().getMultiAssets())
                for (var asset : multi.getAssets())
                    amounts.add(Amount.asset(CardanoAssetUnit.fromTokenContract(multi.getPolicyId() + "." + asset.getNameAsHex()), asset.getValue()));
            utxos.add(Utxo.builder().txHash(hash).outputIndex(item.getTxoRef().getIndex()).address(sender).amount(amounts).build());
        }
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                if (!sender.equals(address) || count == null || count < 1 || page == null || page < 0)
                    throw new IllegalArgumentException("invalid Cardano snapshot page");
                long offset = (long) count * page;
                if (offset >= utxos.size()) return List.of();
                return List.copyOf(utxos.subList((int) offset, (int) Math.min(utxos.size(), offset + count)));
            }
            @Override public Optional<Utxo> getTxOutput(String hash, int index) {
                return utxos.stream().filter(item -> item.getTxHash().equals(hash) && item.getOutputIndex() == index).findFirst();
            }
        };
        TransactionProcessor noSubmission = new TransactionProcessor() {
            @Override public Result<String> submitTransaction(byte[] bytes) {
                throw new IllegalStateException("Cardano public construction cannot submit transactions");
            }
            @Override public Result<List<EvaluationResult>> evaluateTx(byte[] bytes, Set<Utxo> inputs) {
                throw new IllegalStateException("Cardano key payment construction cannot evaluate scripts");
            }
        };
        var pay = CardanoAssetUnit.LOVELACE.equals(normalized) ? Amount.lovelace(amount) : Amount.asset(normalized, amount);
        var tx = new Tx().from(sender).payToAddress(recipient, pay).withChangeAddress(sender);
        long slot = snapshot.getLedgerTip().getSlot();
        var unsigned = new QuickTxBuilder(supplier, () -> protocol, noSubmission).compose(tx).feePayer(sender)
                .additionalSignersCount(1).withSerializationEra(Era.Conway).validFrom(slot)
                .validTo(Math.addExact(slot, lifetimeSlots)).build();
        unsigned.setEra(Era.Conway);
        List<MpcCardanoTransactionCodec.Input> selected = new ArrayList<>();
        try {
            for (var input : unsigned.getBody().getInputs()) {
                var fact = facts.get(input.getTransactionId() + ":" + input.getIndex());
                if (fact == null) throw new IllegalArgumentException("Cardano builder chose an input outside the snapshot");
                selected.add(new MpcCardanoTransactionCodec.Input(input.getTransactionId(), input.getIndex(),
                        HEX.formatHex(fact.getNativeBytes().toByteArray())));
            }
            var intent = new MpcCardanoTransactionCodec.Unsigned(HEX.formatHex(genesis.getGenesis().toByteArray()),
                    Integer.toUnsignedLong(genesis.getCardano().getNetworkMagic()), sender, HEX.formatHex(publicKey), recipient,
                    normalized, amount, unsigned.serializeToHex(), selected);
            // Validate facts and estimate the exact one-witness envelope without accessing a private key.
            MpcCardanoTransactionCodec.signingBytes(intent);
            int signedSize = MpcCardanoTransactionCodec.signedSize(intent);
            BigInteger minimumFee = BigInteger.valueOf(protocol.getMinFeeA()).multiply(BigInteger.valueOf(signedSize))
                    .add(BigInteger.valueOf(protocol.getMinFeeB()));
            if (signedSize > protocol.getMaxTxSize() || unsigned.getBody().getFee().compareTo(minimumFee) < 0)
                throw new IllegalArgumentException("Cardano signed payment exceeds size limit or current minimum fee");
            var minAda = new MinAdaCalculator(protocol);
            for (var out : unsigned.getBody().getOutputs()) {
                if (out.getValue().getCoin().compareTo(minAda.calculateMinAda(out)) < 0)
                    throw new IllegalArgumentException("Cardano output does not meet current minimum ADA");
                if (MpcCardanoTransactionCodec.encode(out.getValue().serialize()).length > Long.parseLong(protocol.getMaxValSize()))
                    throw new IllegalArgumentException("Cardano output exceeds current maximum value size");
            }
            return new Prepared(intent, Sync.BlockRef.newBuilder().setSlot(slot).setHash(snapshot.getLedgerTip().getHash())
                    .setHeight(snapshot.getLedgerTip().getHeight()).setTimestamp(snapshot.getLedgerTip().getTimestamp()).build());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("Cardano public payment construction failed", e);
        }
    }

    public static ProtocolParams protocol(Query.ReadParamsResponse parameters) {
        if (!parameters.hasValues() || !parameters.getValues().hasCardano())
            throw new IllegalArgumentException("Cardano node protocol parameters are absent");
        var p = parameters.getValues().getCardano();
        long major = p.getProtocolVersion().getMajor();
        if (major < 9 || major > 10 || p.getMaxTxSize() < 1 || p.getMaxTxSize() > 65_536
                || p.getMaxValueSize() < 1 || p.getMaxValueSize() > p.getMaxTxSize()
                || CardanoRpcClient.integer(p.getCoinsPerUtxoByte()).signum() <= 0
                || CardanoRpcClient.integer(p.getMinFeeCoefficient()).signum() < 0
                || CardanoRpcClient.integer(p.getMinFeeConstant()).signum() < 0)
            throw new IllegalArgumentException("unsupported Cardano protocol or fee parameters");
        var protocol = new ProtocolParams();
        protocol.setMinFeeA(CardanoRpcClient.integer(p.getMinFeeCoefficient()).intValueExact());
        protocol.setMinFeeB(CardanoRpcClient.integer(p.getMinFeeConstant()).intValueExact());
        protocol.setMaxTxSize(Math.toIntExact(p.getMaxTxSize()));
        protocol.setMaxValSize(Long.toString(p.getMaxValueSize()));
        protocol.setCoinsPerUtxoSize(CardanoRpcClient.integer(p.getCoinsPerUtxoByte()).toString());
        protocol.setProtocolMajorVer(Math.toIntExact(major));
        protocol.setProtocolMinorVer(Math.toIntExact(p.getProtocolVersion().getMinor()));
        return protocol;
    }

    public static TransactionOutput output(Cardano.TxOutput output) {
        // Native node 11.1.3 returns script:{} for ordinary outputs without a reference script.
        if (output.hasDatum() || (output.hasScript() && !output.getScript().equals(Cardano.Script.getDefaultInstance())))
            throw new IllegalArgumentException("Cardano payment snapshot contains datum or script");
        byte[] bytes = output.getAddress().toByteArray();
        if (bytes.length == 0) throw new IllegalArgumentException("Cardano output address is absent");
        String address = Bech32.encodeBytes(Bech32.Encoding.BECH32, (bytes[0] & 15) == 1 ? "addr" : "addr_test", bytes);
        CardanoRpcClient.addressBytes(address);
        var assets = new ArrayList<MultiAsset>();
        for (var group : output.getAssetsList()) {
            var values = new ArrayList<Asset>();
            for (var asset : group.getAssetsList()) {
                String name = "0x" + HEX.formatHex(asset.getName().toByteArray());
                values.add(new Asset(name, CardanoRpcClient.integer(asset.getQuantity())));
            }
            assets.add(new MultiAsset(HEX.formatHex(group.getPolicyId().toByteArray()), values));
        }
        return new TransactionOutput(address, Value.builder().coin(CardanoRpcClient.integer(output.getCoin())).multiAssets(assets).build());
    }

    public static TransactionOutput output(Query.AnyUtxoData fact) {
        if (!fact.hasCardano() || fact.getNativeBytes().isEmpty() || fact.getNativeBytes().size() > 65_536)
            throw new IllegalArgumentException("Cardano original unspent output bytes are absent or oversized");
        try {
            var parsed = output(fact.getCardano());
            var original = TransactionOutput.deserialize(MpcCardanoTransactionCodec.decode(fact.getNativeBytes().toByteArray()));
            if (!java.util.Arrays.equals(MpcCardanoTransactionCodec.encode(parsed.serialize()), MpcCardanoTransactionCodec.encode(original.serialize())))
                throw new IllegalArgumentException("Cardano parsed UTxO differs from original output bytes");
            return original;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid Cardano original unspent output", e);
        }
    }
}

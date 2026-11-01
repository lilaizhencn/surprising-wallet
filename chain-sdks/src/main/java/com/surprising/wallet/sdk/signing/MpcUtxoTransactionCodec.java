package com.surprising.wallet.sdk.signing;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.ScriptBuilder;

/** Joint-key BTC/LTC P2WPKH and DOGE/BCH P2PKH; unsigned bytes are fixed before external signing. */
public final class MpcUtxoTransactionCodec {
    private static final HexFormat HEX = HexFormat.of();
    private MpcUtxoTransactionCodec() { }

    public record Input(String txId, int outputIndex, long valueAtomic, String publicKeyHex) {
        public Input {
            if (txId == null || !txId.matches("[0-9a-fA-F]{64}") || outputIndex < 0 || valueAtomic <= 0
                    || publicKeyHex == null || !publicKeyHex.matches("0[23][0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid MPC UTXO input");
            }
            txId = txId.toLowerCase(java.util.Locale.ROOT);
            ECKey.fromPublicOnly(HEX.parseHex(publicKeyHex));
        }
    }
    public record Payment(String address, long valueAtomic) {
        public Payment {
            if (address == null || address.isBlank() || valueAtomic <= 0) throw new IllegalArgumentException("invalid UTXO payment");
        }
    }
    public record Unsigned(String chain, BitcoinLikeNetwork network, String transactionHex, List<Input> inputs,
                           long feeAtomic, long estimatedVBytes) {
        public Unsigned {
            Objects.requireNonNull(network, "UTXO network is required").parameters(chain);
            if (transactionHex == null || transactionHex.isEmpty() || transactionHex.length() % 2 != 0
                    || !transactionHex.matches("[0-9a-f]+") || feeAtomic < 0 || estimatedVBytes <= 0) {
                throw new IllegalArgumentException("invalid immutable UTXO envelope");
            }
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) throw new IllegalArgumentException("UTXO inputs are required");
        }
    }
    public record Signed(String transactionHex, String txId, long feeAtomic, long weight, long vBytes) { }

    /** Maximum canonical signature size, with the actual output scripts and compact-size counts. */
    public static long estimateVBytes(String chain, BitcoinLikeNetwork network, List<Input> inputs, List<String> outputs) {
        if (inputs.isEmpty() || outputs.isEmpty()) throw new IllegalArgumentException("UTXO sizing requires inputs and outputs");
        return estimate(chain, build(chain, network, inputs, outputs.stream().map(address -> new Payment(address, 1)).toList()), inputs);
    }

    public static long feeAtomic(String chain, long size, long rate) {
        if (size <= 0 || rate <= 0) throw new IllegalArgumentException("positive UTXO size and fee rate are required");
        return fee(chain, size, rate);
    }

    public static Unsigned prepare(String chain, BitcoinLikeNetwork network, List<Input> inputs, List<Payment> payments,
                                   String changeAddress, long feeRate, long dustThreshold) {
        Objects.requireNonNull(network, "UTXO network is required").parameters(chain);
        inputs = List.copyOf(inputs);
        payments = List.copyOf(payments);
        if (inputs.isEmpty() || payments.isEmpty() || feeRate <= 0 || dustThreshold <= 0) {
            throw new IllegalArgumentException("UTXO inputs, payments, fee rate and dust threshold are required");
        }
        var points = new HashSet<String>();
        long total = 0;
        for (var input : inputs) {
            if (!points.add(input.txId() + ":" + input.outputIndex())) throw new IllegalArgumentException("duplicate UTXO outpoint");
            total = Math.addExact(total, input.valueAtomic());
        }
        long paid = 0;
        for (var payment : payments) {
            if (payment.valueAtomic() < dustThreshold) throw new IllegalArgumentException("UTXO payment is below dust threshold");
            paid = Math.addExact(paid, payment.valueAtomic());
        }
        long remaining = Math.subtractExact(total, paid);
        long noChangeSize = estimate(chain, build(chain, network, inputs, payments), inputs);
        long minimumFee = fee(chain, noChangeSize, feeRate);
        if (remaining < minimumFee) throw new IllegalArgumentException("insufficient UTXO funds for payment and fee");
        var outputs = new ArrayList<>(payments);
        long selectedSize = noChangeSize;
        long selectedFee = remaining;
        if (remaining - minimumFee >= dustThreshold) {
            if (changeAddress == null || changeAddress.isBlank()) throw new IllegalArgumentException("UTXO change address is required");
            var estimateOutputs = new ArrayList<>(outputs);
            estimateOutputs.add(new Payment(changeAddress, dustThreshold));
            long changeSize = estimate(chain, build(chain, network, inputs, estimateOutputs), inputs);
            long changeFee = fee(chain, changeSize, feeRate);
            long change = remaining - changeFee;
            if (change >= dustThreshold) {
                outputs.add(new Payment(changeAddress, change));
                selectedFee = changeFee;
                selectedSize = changeSize;
            }
        }
        var unsigned = new Unsigned(chain, network, HEX.formatHex(build(chain, network, inputs, outputs).bitcoinSerialize()),
                inputs, selectedFee, selectedSize);
        restore(unsigned);
        return unsigned;
    }

    public static byte[] digest(Unsigned unsigned, int inputIndex) {
        var transaction = restore(unsigned);
        var input = unsigned.inputs().get(inputIndex);
        var script = ScriptBuilder.createP2PKHOutputScript(ECKey.fromPublicOnly(HEX.parseHex(input.publicKeyHex())));
        Sha256Hash digest = switch (unsigned.chain()) {
            case "BTC", "LTC" -> transaction.hashForWitnessSignature(inputIndex, script.program(), Coin.valueOf(input.valueAtomic()), (byte) 1);
            case "BCH" -> transaction.hashForWitnessSignature(inputIndex, script.program(), Coin.valueOf(input.valueAtomic()), (byte) 0x41);
            case "DOGE" -> transaction.hashForSignature(inputIndex, script, Transaction.SigHash.ALL, false);
            default -> throw new IllegalArgumentException("unsupported MPC UTXO chain");
        };
        return digest.getBytes();
    }

    public static Signed assemble(Unsigned unsigned, List<byte[]> signatures) {
        var transaction = restore(unsigned);
        if (signatures.size() != unsigned.inputs().size()) throw new IllegalArgumentException("UTXO signature count differs from inputs");
        for (int i = 0; i < signatures.size(); i++) {
            var metadata = unsigned.inputs().get(i);
            var key = ECKey.fromPublicOnly(HEX.parseHex(metadata.publicKeyHex()));
            if (!ThresholdSignatureVerifier.ecdsa(key.getPubKey(), digest(unsigned, i), signatures.get(i))) {
                throw new IllegalArgumentException("MPC signature differs from the accepted UTXO input");
            }
            ECKey.ECDSASignature decoded;
            try { decoded = ECKey.ECDSASignature.decodeFromDER(signatures.get(i)).toCanonicalised(); }
            catch (org.bitcoinj.crypto.SignatureDecodeException invalid) { throw new IllegalArgumentException("invalid UTXO DER signature", invalid); }
            var signature = new TransactionSignature(decoded.r, decoded.s, unsigned.chain().equals("BCH") ? 0x41 : 1);
            var input = transaction.getInput(i);
            transaction.replaceInput(i, segwit(unsigned.chain())
                    ? input.withWitness(TransactionWitness.of(signature.encodeToBitcoin(), key.getPubKey()))
                    : input.withScriptSig(ScriptBuilder.createInputScript(signature, key)));
        }
        if (transaction.getVsize() > unsigned.estimatedVBytes()) throw new IllegalStateException("signed UTXO exceeds its fee size bound");
        return new Signed(HEX.formatHex(transaction.bitcoinSerialize()), transaction.getTxId().toString(), unsigned.feeAtomic(),
                transaction.getWeight(), transaction.getVsize());
    }

    private static Transaction restore(Unsigned unsigned) {
        var transaction = Transaction.read(ByteBuffer.wrap(HEX.parseHex(unsigned.transactionHex())));
        if (!HEX.formatHex(transaction.bitcoinSerialize()).equals(unsigned.transactionHex())
                || transaction.getInputs().size() != unsigned.inputs().size() || transaction.getOutputs().isEmpty()) {
            throw new IllegalArgumentException("noncanonical UTXO envelope or input count mismatch");
        }
        long total = 0;
        var points = new HashSet<String>();
        for (int i = 0; i < unsigned.inputs().size(); i++) {
            var metadata = unsigned.inputs().get(i);
            var input = transaction.getInput(i);
            if (!points.add(metadata.txId() + ":" + metadata.outputIndex()) || input.getScriptSig().program().length != 0
                    || input.hasWitness() || !input.getOutpoint().getHash().toString().equals(metadata.txId())
                    || input.getOutpoint().getIndex() != metadata.outputIndex()) {
                throw new IllegalArgumentException("UTXO envelope differs from its unsigned input metadata");
            }
            total = Math.addExact(total, metadata.valueAtomic());
        }
        long paid = 0;
        for (var output : transaction.getOutputs()) {
            if (output.getValue().signum() <= 0) throw new IllegalArgumentException("UTXO output must be positive");
            paid = Math.addExact(paid, output.getValue().value);
        }
        if (Math.subtractExact(total, paid) != unsigned.feeAtomic()) throw new IllegalArgumentException("UTXO fee differs from accepted input/output amounts");
        return transaction;
    }

    private static Transaction build(String chain, BitcoinLikeNetwork network, List<Input> inputs, List<Payment> outputs) {
        var parameters = network.parameters(chain);
        var transaction = new Transaction(parameters);
        for (var input : inputs) transaction.addInput(new TransactionInput(transaction, new byte[0],
                new TransactionOutPoint(input.outputIndex(), Sha256Hash.wrap(input.txId())), Coin.valueOf(input.valueAtomic()))
                .withSequence(segwit(chain) ? 0xfffffffdL : 0xffffffffL));
        for (var output : outputs) {
            Address address = chain.equals("BCH") && (output.address().contains(":")
                    || output.address().startsWith("q") || output.address().startsWith("p"))
                    ? BitcoinCashAddressCodec.toLegacy((BitcoinCashNetworkParameters) parameters,
                            ((BitcoinCashNetworkParameters) parameters).cashPrefix(), output.address())
                    : Address.fromString(parameters, output.address());
            transaction.addOutput(Coin.valueOf(output.valueAtomic()), address);
        }
        return transaction;
    }

    private static long estimate(String chain, Transaction transaction, List<Input> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            byte[] publicKey = HEX.parseHex(inputs.get(i).publicKeyHex());
            var input = transaction.getInput(i);
            transaction.replaceInput(i, segwit(chain)
                    ? input.withWitness(TransactionWitness.of(new byte[73], publicKey))
                    : input.withScriptSig(new ScriptBuilder().data(new byte[73]).data(publicKey).build()));
        }
        return transaction.getVsize();
    }
    private static boolean segwit(String chain) { return chain.equals("BTC") || chain.equals("LTC"); }
    private static long fee(String chain, long size, long rate) {
        return chain.equals("DOGE") ? DogecoinFeePolicy.feeForBytes(size, rate) : Math.multiplyExact(size, rate);
    }
}
